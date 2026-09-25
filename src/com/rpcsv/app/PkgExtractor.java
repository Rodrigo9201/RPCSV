package com.rpcsv.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Standalone PS Vita PKG extractor for RPCSV (Android).
 * Faithful port of the pkg2zip (mmozeiko) decryption path using only
 * java.* + javax.crypto so the same code can be unit-tested on a desktop JVM.
 */
public final class PkgExtractor {

    private static final byte[] PKG_PS3_KEY = fromHex("2e7b71d7c9c9a14ea3221f188828b8f8");
    private static final byte[] PKG_PSP_KEY = fromHex("07f2c68290b50d2c33818d709b60e62b");
    private static final byte[] PKG_VITA_2 = fromHex("e31a70c9ce1dd72bf3c0622963f2eccb");
    private static final byte[] PKG_VITA_3 = fromHex("423aca3a2bd5649f9686abad6fd8801f");
    private static final byte[] PKG_VITA_4 = fromHex("af07fd59652527baf13389668b17d9ea");
    private static final int ZLIB_DICTIONARY_ID_ZRIF = 0x627d1d5d;

    // PFS constants, ported 1:1 from gumshoe (crate gumshoe 0.0.3-beta,
    // src/headerck/sony/psv/{pfs.rs,decrypt.rs}).
    private static final byte[] PFS_CONTRACT_KEY = fromHex("e12213b48016b0e99ab81f8ec02ad4a2");
    private static final byte[] PFS_HMAC_KEY = fromHex("e462258b1f3121560745db62b1436723d2bf80fe");
    private static final byte[] PFS_SECRET_HMAC_KEY = fromHex("afe656bb3c17256a3c809f6e9bf19fdd5a388543");
    private static final byte[] PFS_SECRET_IV = fromHex("74d20cc39881c213ee770b1010e4bea7");
    private static final int PFS_UNICV_TABLE_SIZE = 72;

    private static byte[] fromHex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    private static int b16le(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private static int b32le(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24); }
    private static int b32be(byte[] b, int o) { return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff); }
    private static long b64be(byte[] b, int o) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[o + i] & 0xff);
        return v;
    }

    private static byte[] readAt(RandomAccessFile raf, long pos, int len) throws Exception {
        byte[] b = new byte[len];
        raf.seek(pos);
        int got = 0;
        while (got < len) {
            int r = raf.read(b, got, len - got);
            if (r < 0) throw new Exception("pkg truncado em " + (pos + got));
            got += r;
        }
        return b;
    }

    private static byte[] aesEcb(byte[] key, byte[] block) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(block);
    }

    private static void ctrAdd(byte[] ct, long n) {
        long carry = n;
        for (int i = 15; carry != 0 && i >= 0; i--) {
            long v = (ct[i] & 0xffL) + (carry & 0xffL);
            ct[i] = (byte) (v & 0xff);
            carry = (carry >>> 8) + (v >> 8);
        }
    }

    /** pkg2zip aes128_ctr_xor over a buffer; keystream = AES(key, iv + block). */
    static byte[] xorCtr(byte[] key, byte[] iv, long startBlock, byte[] buf) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] ct = iv.clone();
        ctrAdd(ct, startBlock);
        int n = buf.length;
        if (n == 0) return buf;
        int blocks = (n + 15) / 16;
        byte[] inb = new byte[blocks * 16];
        for (int b = 0; b < blocks; b++) {
            System.arraycopy(ct, 0, inb, b * 16, 16);
            ctrAdd(ct, 1);
        }
        byte[] ks = c.doFinal(inb);
        byte[] out = buf.clone();
        for (int i = 0; i < n; i++) out[i] ^= ks[i];
        return out;
    }

    private static byte[] readSfoBytes(String sfoPath) throws Exception {
        File f = new File(sfoPath);
        if (!f.isFile()) return null;
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            byte[] raw = new byte[(int) Math.min(f.length(), 4 << 20)];
            int got = 0;
            while (got < raw.length) {
                int r = in.read(raw, got, raw.length - got);
                if (r <= 0) break;
                got += r;
            }
            if (got != raw.length) {
                byte[] c = new byte[got];
                System.arraycopy(raw, 0, c, 0, got);
                raw = c;
            }
            return raw;
        } finally {
            in.close();
        }
    }

    public static String readParamTitleId(String sfoPath) {
        try {
            byte[] raw = readSfoBytes(sfoPath);
            if (raw == null) return "";
            Map<String, Object> m = parseSfo(raw);
            Object t = m.get("TITLE_ID");
            if (t == null) {
                String ci = str(m.get("CONTENT_ID"));
                if (ci.length() >= 16) return ci.substring(7, 16);
                return "";
            }
            return String.valueOf(t).replace("\u0000", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static String readParamTitle(String sfoPath) {
        try {
            if (sfoPath == null || sfoPath.isEmpty()) return "";
            File f = new File(sfoPath);
            if (!f.isFile()) return "";
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] raw;
            try {
                raw = new byte[in.available()];
                int got = 0;
                while (got < raw.length) {
                    int r = in.read(raw, got, raw.length - got);
                    if (r <= 0) break;
                    got += r;
                }
                if (got != raw.length) {
                    byte[] c = new byte[got];
                    System.arraycopy(raw, 0, c, 0, got);
                    raw = c;
                }
            } finally {
                in.close();
            }
            Map<String, Object> m = parseSfo(raw);
            Object t = m.get("TITLE");
            if (t == null) t = m.get("TITLE_00");
            return t == null ? "" : String.valueOf(t).replace("\u0000", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, Object> parseSfo(byte[] sfo) throws Exception {
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        if (sfo.length < 20 || b32le(sfo, 0) != 0x46535000) throw new Exception("SFO inválido");
        int keys = b32le(sfo, 8);
        int values = b32le(sfo, 12);
        int count = b32le(sfo, 16);
        for (int i = 0; i < count; i++) {
            int e = 20 + i * 16;
            if (e + 16 > sfo.length) break;
            int keyOff = b16le(sfo, e);
            int fmt = (sfo[e + 4] & 0xff) | ((sfo[e + 5] & 0xff) << 8);
            int valOff = b32le(sfo, e + 12);
            StringBuilder kb = new StringBuilder();
            int p = keys + keyOff;
            while (p < sfo.length && sfo[p] != 0) kb.append((char) (sfo[p++] & 0xff));
            String key = kb.toString();
            if (fmt == 0x0404) {
                int pos = values + valOff;
                res.put(key, Integer.valueOf(pos + 4 <= sfo.length ? b32le(sfo, pos) : 0));
            } else {
                int q = values + valOff;
                if (q >= sfo.length) { res.put(key, ""); continue; }
                int end = q;
                while (end < sfo.length && sfo[end] != 0) end++;
                res.put(key, new String(sfo, q, Math.max(0, end - q), "UTF-8"));
            }
        }
        return res;
    }

    static byte[] zrifDecode(String text) throws Exception {
        byte[] raw = Base64.getDecoder().decode(text.replaceAll("[^A-Za-z0-9+/=]", ""));
        if (raw.length < 6) throw new Exception("zRIF muito curto");
        if ((((raw[0] & 0xff) << 8) + (raw[1] & 0xff)) % 31 != 0) throw new Exception("zRIF corrompido");
        if ((raw[0] & 0xf) != 8) throw new Exception("método zRIF não suportado");
        byte[] data;
        byte[] dict = null;
        if ((raw[1] & 0x20) != 0) {
            int id = b32be(raw, 2);
            if (id != ZLIB_DICTIONARY_ID_ZRIF) throw new Exception("dicionário zRIF desconhecido");
            data = new byte[raw.length - 6];
            System.arraycopy(raw, 6, data, 0, data.length);
            dict = ZRIF_DICT;
        } else {
            data = new byte[raw.length - 2];
            System.arraycopy(raw, 2, data, 0, data.length);
        }
        Inflater inf = new Inflater(true);
        inf.setInput(data);
        if (dict.length > 0) inf.setDictionary(dict);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        while (!inf.finished()) {
            int r = inf.inflate(tmp);
            if (r == 0 && inf.needsDictionary()) throw new Exception("dicionário zRIF ausente");
            if (r == 0 && inf.needsInput()) break;
            if (r > 0) bos.write(tmp, 0, r);
        }
        inf.end();
        return bos.toByteArray();
    }

    /** Extracts a PKG into baseDir/ux0/app/<TITLEID>/ (or pspemu/...). */
    public static Map<String, Object> install(String pkgPath, String zrifText, String workbinPath, String baseDir) throws Exception {
        if (pkgPath == null || pkgPath.isEmpty()) throw new Exception("caminho do PKG inválido");
        File pf = new File(pkgPath);
        if (!pf.exists()) throw new Exception("arquivo não encontrado: " + pkgPath);
        if (!pf.isFile()) throw new Exception("arquivo não é um arquivo válido: " + pkgPath + " (use o menu de arquivos do app para escolher o PKG)");
        RandomAccessFile raf = new RandomAccessFile(pkgPath, "r");
        try {
            byte[] header = readAt(raf, 0, 512);
            if (b32be(header, 0) != 0x7f504b47 || b32be(header, 192) != 0x7f657874) {
                throw new Exception("arquivo não é um PKG válido (suportamos PKG digital da PlayStation Store Vita)");
            }
            int metaOffset = b32be(header, 8);
            int metaCount = b32be(header, 12);
            int itemCount = b32be(header, 20);
            long encOffset = b64be(header, 32);
            long encSize = b64be(header, 40);
            byte[] iv = new byte[16];
            System.arraycopy(header, 0x70, iv, 0, 16);
            int keyType = header[0xe7] & 7;

            if (baseDir == null || baseDir.isEmpty()) throw new Exception("diretório de instalação não definido");
            if (metaCount > 0x1000) throw new Exception("cabeçalho de PKG inválido (metaCount)");
            if (itemCount > 0x1000000) throw new Exception("lista de arquivos inválida (itemCount)");
            if (encOffset < 0 || encSize < 0 || encOffset + encSize > raf.length()) throw new Exception("pkg truncado (região cifrada)");

            int contentType = 0, sfoOffset = 0, sfoSize = 0, itemsOffset = 0, itemsSize = 0;
            long mo = metaOffset;
            for (int i = 0; i < metaCount; i++) {
                byte[] block = readAt(raf, mo, 16);
                int type = b32be(block, 0);
                int size = b32be(block, 4);
                if (type == 2) contentType = b32be(block, 8);
                else if (type == 13) { itemsOffset = b32be(block, 8); itemsSize = b32be(block, 12); }
                else if (type == 14) { sfoOffset = b32be(block, 8); sfoSize = b32be(block, 12); }
                mo += 8 + size;
            }

            String pkgType;
            if (contentType == 6) pkgType = "psx";
            else if (contentType == 7 || contentType == 0xe || contentType == 0xf || contentType == 0x10) pkgType = "psp";
            else if (contentType == 0x15) pkgType = "app";
            else if (contentType == 0x16) pkgType = "dlc";
            else if (contentType == 0x18 || contentType == 0x1d) pkgType = "psm";
            else throw new Exception("tipo de conteúdo não suportado: 0x" + Integer.toHexString(contentType));

            byte[] mainKey;
            if (keyType == 1) mainKey = PKG_PSP_KEY.clone();
            else if (keyType == 2) mainKey = aesEcb(PKG_VITA_2, iv);
            else if (keyType == 3) mainKey = aesEcb(PKG_VITA_3, iv);
            else if (keyType == 4) mainKey = aesEcb(PKG_VITA_4, iv);
            else throw new Exception("chave de pkg não suportada: " + keyType);

            Map<String, Object> sfo;
            try {
                sfo = parseSfo(readAt(raf, sfoOffset, sfoSize));
            } catch (Exception e) {
                sfo = new LinkedHashMap<String, Object>();
            }
            String contentId = str(sfo.get("CONTENT_ID"));
            String title = str(sfo.get("TITLE"));
            String category = str(sfo.get("CATEGORY"));
            String id;
            if ("psp".equals(pkgType) || "psx".equals(pkgType)) {
                id = new String(header, 0x37, 9, "ISO-8859-1");
            } else if (!contentId.isEmpty()) {
                id = contentId.substring(7, 16);
            } else {
                throw new Exception("não foi possível determinar o TITLE_ID");
            }

            // Decode + validate the licence BEFORE writing anything (fail fast).
            byte[] rif = null;
            if (workbinPath != null && !workbinPath.isEmpty()) {
                File wf = new File(workbinPath);
                if (!wf.exists() || !wf.isFile()) throw new Exception("work.bin não encontrado: " + workbinPath);
                java.io.FileInputStream in = new java.io.FileInputStream(wf);
                try {
                    rif = new byte[in.available()];
                    int got = 0;
                    while (got < rif.length) {
                        int r = in.read(rif, got, rif.length - got);
                        if (r <= 0) break;
                        got += r;
                    }
                    if (got != rif.length) {
                        byte[] c = new byte[got];
                        System.arraycopy(rif, 0, c, 0, got);
                        rif = c;
                    }
                } finally {
                    in.close();
                }
            } else if (zrifText != null && !zrifText.trim().isEmpty()) {
                rif = zrifDecode(zrifText);
            }
            if (rif != null && rif.length != 512 && rif.length != 1024) {
                throw new Exception("Licença inválida (work.bin deve ter 512 ou 1024 bytes, obteve " + rif.length + ")");
            }

            String rootName;
            if ("app".equals(pkgType) || "psm".equals(pkgType)) rootName = "ux0/app/" + id;
            else if ("dlc".equals(pkgType)) rootName = "ux0/addcont/" + id + "/" + (contentId.length() >= 24 ? contentId.substring(16, 24) : "content");
            else rootName = "pspemu/PSP/GAME/" + id;

            File baseDirF = new File(baseDir, rootName);
            byte[] itemTab = xorCtr(mainKey, iv, itemsOffset / 16, readAt(raf, encOffset + itemsOffset, itemCount * 32));

            boolean sceSysPkg = baseDirF.isDirectory() && new File(baseDirF, "sce_sys/package").isDirectory();
            for (int i = 0; i < itemCount; i++) {
                byte[] it = new byte[32];
                System.arraycopy(itemTab, i * 32, it, 0, 32);
                int nameOffset = b32be(it, 0);
                int nameSize = b32be(it, 4);
                long dataOffset = b64be(it, 8);
                long dataSize = b64be(it, 16);
                int pspType = it[24] & 0xff;
                int flags = it[27] & 0xff;

                byte[] itemKey = ("psp".equals(pkgType) || "psx".equals(pkgType)) ? (pspType == 0x90 ? mainKey : PKG_PS3_KEY) : mainKey;
                String name = new String(xorCtr(itemKey, iv, nameOffset / 16, readAt(raf, encOffset + nameOffset, nameSize)), "UTF-8");
                if (name.startsWith("/") || name.contains("..")) continue;

                if (flags == 4 || flags == 18) {
                    if ("sce_sys/package".equals(name)) sceSysPkg = true;
                    new File(baseDirF, name).mkdirs();
                    continue;
                }

                boolean decrypt = !(("app".equals(pkgType) || "dlc".equals(pkgType)) && "sce_sys/package/digs.bin".equals(name));
                File out = new File(baseDirF, name);
                if (out.getParentFile() != null) out.getParentFile().mkdirs();
                FileOutputStream os = new FileOutputStream(out);
                long offset = dataOffset;
                long remain = dataSize;
                while (remain > 0) {
                    int chunk = (int) Math.min(remain, 65536);
                    byte[] raw = readAt(raf, encOffset + offset, chunk);
                    if (decrypt) raw = xorCtr(itemKey, iv, offset / 16, raw);
                    os.write(raw, 0, raw.length);
                    offset += chunk;
                    remain -= chunk;
                }
                os.close();
            }

            if ("app".equals(pkgType) || "dlc".equals(pkgType) || "psm".equals(pkgType)) {
                File pkgDir = new File(baseDirF, "sce_sys/package");
                pkgDir.mkdirs();
                long headSize = encOffset + itemsSize;
                if (headSize > raf.length() || headSize > Integer.MAX_VALUE) throw new Exception("pkg truncado (head)");
                writeTo(new File(pkgDir, "head.bin"), readAt(raf, 0, (int) headSize));
                long tailStart = encOffset + encSize;
                long tailLen = raf.length() - tailStart;
                if (tailStart < 0 || tailStart > raf.length()) throw new Exception("pkg truncado (tail)");
                writeTo(new File(pkgDir, "tail.bin"), readAt(raf, tailStart, (int) tailLen));
                writeTo(new File(pkgDir, "stat.bin"), new byte[768]);
                if (rif != null) {
                    writeTo(new File(pkgDir, "work.bin"), rif);
                }
            }

            // PFS: decrypt game content sealed inside sce_pfs (files.db / unicv.db).
            // Only files whose table signature AND size match are rewritten, so a wrong
            // licence never corrupts an extraction.
            int pfsFiles = 0;
            String pfsError = null;
            if (rif != null && !"psm".equals(pkgType) && new File(baseDirF, "sce_pfs").exists()) {
                try {
                    pfsFiles = decryptPfsDir(baseDirF, rif);
                } catch (Exception e) {
                    pfsFiles = -1;
                    pfsError = String.valueOf(e.getMessage());
                }
            }

            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("ok", Boolean.TRUE);
            r.put("mode", "standalone");
            r.put("kind", pkgType);
            r.put("titleId", id);
            r.put("title", title);
            r.put("category", category);
            r.put("contentId", contentId);
            r.put("appDir", baseDirF.getAbsolutePath());
            r.put("pfsFiles", Integer.valueOf(pfsFiles));
            r.put("keyType", Integer.valueOf(keyType));
            r.put("itemCount", Integer.valueOf(itemCount));
            if (pfsError != null) r.put("pfsError", pfsError);
            return r;
        } finally {
            raf.close();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static void writeTo(File f, byte[] data) throws Exception {
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        FileOutputStream os = new FileOutputStream(f);
        os.write(data);
        os.close();
    }

    // zRIF deflate preset dictionary (pkg2zip_zrif.c), exactly 1024 bytes.
    private static final byte[] ZRIF_DICT = {
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
        48,48,48,48,57,0,0,0,0,0,0,0,0,0,0,0,
        0,48,48,48,48,54,48,48,48,48,55,48,48,48,48,56,
        0,48,48,48,48,51,48,48,48,48,52,48,48,48,48,53,
        48,95,48,48,45,65,68,68,67,79,78,84,48,48,48,48,
        50,45,80,67,83,71,48,48,48,48,48,48,48,48,48,48,
        49,45,80,67,83,69,48,48,48,45,80,67,83,70,48,48,
        48,45,80,67,83,67,48,48,48,45,80,67,83,68,48,48,
        48,45,80,67,83,65,48,48,48,45,80,67,83,66,48,48,
        48,0,1,0,1,0,1,0,2,-17,-51,-85,-119,103,69,35,1
    };

    private static final class PfsTable {
        long salt, sectors, sectorSize;
        byte[] dbseed, firstSignature;
    }

    private static final class PfsCandidate {
        PfsTable table;
        byte[] signatureKey;
    }

    private static byte[] hmacSha1(byte[] key, byte[] msg) throws Exception {
        Mac m = Mac.getInstance("HmacSHA1");
        m.init(new SecretKeySpec(key, "HmacSHA1"));
        return m.doFinal(msg);
    }

    private static byte[] aesBlock(byte[] key, byte[] block, boolean decrypt) throws Exception {
        Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
        c.init(decrypt ? Cipher.DECRYPT_MODE : Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return c.doFinal(block);
    }

    private static byte[] aesCbc(byte[] key, byte[] iv, byte[] data, boolean decrypt) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(decrypt ? Cipher.DECRYPT_MODE : Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    private static byte[] le32(long n) {
        return new byte[] { (byte) n, (byte) (n >>> 8), (byte) (n >>> 16), (byte) (n >>> 24) };
    }

    private static long asUnsigned(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    private static List<PfsTable> parseUnicv(byte[] bytes) throws Exception {
        List<PfsTable> tables = new ArrayList<PfsTable>();
        if (bytes.length < 0x30) return tables;
        if (bytes[0] != 'S' || bytes[1] != 'C' || bytes[2] != 'E' || bytes[3] != 'I'
                || bytes[4] != 'R' || bytes[5] != 'O' || bytes[6] != 'D' || bytes[7] != 'B') return tables;
        long pageSize = asUnsigned(bytes, 12);
        long dataSize = asUnsigned(bytes, 24);
        long dataEnd = pageSize + dataSize;
        if (pageSize == 0 || dataEnd > bytes.length) return tables;
        long offset = pageSize;
        while (offset < dataEnd) {
            int o = (int) offset;
            boolean tbl = o + 8 <= bytes.length
                    && bytes[o] == 'S' && bytes[o + 1] == 'C' && bytes[o + 2] == 'E' && bytes[o + 3] == 'I'
                    && bytes[o + 4] == 'F' && bytes[o + 5] == 'T' && bytes[o + 6] == 'B' && bytes[o + 7] == 'L';
            if (tbl) {
                long version = asUnsigned(bytes, o + 8);
                long tablePageSize = asUnsigned(bytes, o + 12);
                long maxSignatures = asUnsigned(bytes, o + 16);
                long sectors = asUnsigned(bytes, o + 20);
                long sectorSize = asUnsigned(bytes, o + 24);
                byte[] dbseed = new byte[20];
                System.arraycopy(bytes, o + 52, dbseed, 0, 20);
                long salt = offset / pageSize;
                if (version != 2 || tablePageSize != pageSize || maxSignatures == 0) {
                    return new ArrayList<PfsTable>();
                }
                long signaturePage = ((offset + PFS_UNICV_TABLE_SIZE + pageSize - 1) / pageSize) * pageSize;
                if (sectors != 0) {
                    int sp = (int) signaturePage;
                    if (sp + 36 > bytes.length) return new ArrayList<PfsTable>();
                    byte[] firstSig = new byte[20];
                    System.arraycopy(bytes, sp + 16, firstSig, 0, 20);
                    PfsTable t = new PfsTable();
                    t.salt = salt;
                    t.sectors = sectors;
                    t.sectorSize = sectorSize;
                    t.dbseed = dbseed;
                    t.firstSignature = firstSig;
                    tables.add(t);
                }
                long signaturePages = ((sectors + maxSignatures - 1) / maxSignatures) * pageSize;
                offset = signaturePage + signaturePages;
            } else {
                offset += pageSize;
            }
        }
        return tables;
    }

    private static long filesSalt(byte[] filesDb) {
        if (filesDb.length < 32) return 0;
        if (bytesEqualAscii(filesDb, 0, "SCENGPFS")) return asUnsigned(filesDb, 28);
        return 0;
    }

    private static boolean bytesEqualAscii(byte[] b, int o, String s) {
        if (o + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) {
            if ((b[o + i] & 0xff) != (s.charAt(i) & 0xff)) return false;
        }
        return true;
    }

    private static byte[] dataKey(byte[] klicensee) throws Exception {
        return aesBlock(PFS_CONTRACT_KEY, klicensee, true);
    }

    private static byte[] signatureKey(byte[] dk, long fSalt, PfsTable table) throws Exception {
        byte[] salt8 = new byte[8];
        byte[] a = le32(fSalt);
        byte[] b = le32(table.salt);
        System.arraycopy(a, 0, salt8, 0, 4);
        System.arraycopy(b, 0, salt8, 4, 4);
        byte[] base = hmacSha1(PFS_SECRET_HMAC_KEY, salt8);
        byte[] secret = new byte[20];
        byte[] blk = aesCbc(dk, PFS_SECRET_IV, java.util.Arrays.copyOfRange(base, 0, 16), false);
        System.arraycopy(blk, 0, secret, 0, 16);
        byte[] enc = aesBlock(dk, java.util.Arrays.copyOfRange(secret, 0, 16), false);
        for (int i = 0; i < 4; i++) secret[16 + i] = (byte) (base[16 + i] ^ enc[i]);
        return hmacSha1(secret, le32(0));
    }

    private static void decryptSector(byte[] bytes, byte[] key, byte[] tweakMask, long sector, long sectorSize)
            throws Exception {
        long byteOffset = sector * sectorSize;
        byte[] iv = new byte[16];
        System.arraycopy(tweakMask, 0, iv, 0, 16);
        for (int i = 0; i < 8; i++) iv[i] ^= (byte) (byteOffset & 0xff);
        int aligned = bytes.length & ~0xf;
        byte[] tailIv = null;
        if (aligned != 0 && aligned != bytes.length) {
            tailIv = java.util.Arrays.copyOfRange(bytes, aligned - 16, aligned);
        }
        if (aligned != 0) {
            byte[] dec = aesCbc(key, iv, java.util.Arrays.copyOf(bytes, aligned), true);
            System.arraycopy(dec, 0, bytes, 0, aligned);
        }
        if (aligned != bytes.length) {
            byte[] src = tailIv != null ? tailIv : iv;
            byte[] encIv = aesBlock(key, src, false);
            for (int i = 0; i < bytes.length - aligned; i++) {
                bytes[aligned + i] ^= encIv[i];
            }
        }
    }

    private static PfsTable matchingTable(List<PfsCandidate> candidates, long totalLen, byte[] bytes) throws Exception {
        for (PfsCandidate c : candidates) {
            long ss = c.table.sectorSize;
            if (ss != 0 && ((totalLen + ss - 1) / ss) == c.table.sectors) {
                int lim = (int) Math.min(bytes.length, ss);
                byte[] sig = hmacSha1(c.signatureKey, java.util.Arrays.copyOf(bytes, lim));
                if (java.util.Arrays.equals(sig, c.table.firstSignature)) return c.table;
            }
        }
        return null;
    }

    // Returns the matching table when the file's first sector matches, else null.
    private static PfsTable matchingTable(List<PfsCandidate> candidates, byte[] bytes) throws Exception {
        return matchingTable(candidates, bytes.length, bytes);
    }

    // Returns plaintext when the file matches a table, else null (leave untouched).
    private static byte[] decryptFile(byte[] ct, List<PfsCandidate> candidates, byte[] dk) throws Exception {
        if (candidates.isEmpty()) return null;
        PfsTable table = matchingTable(candidates, ct);
        if (table == null) return null;
        byte[] tweakMask = hmacSha1(PFS_HMAC_KEY, table.dbseed);
        byte[] out = new byte[ct.length];
        System.arraycopy(ct, 0, out, 0, ct.length);
        long sector = 0;
        for (int off = 0; off < out.length; off += (int) table.sectorSize) {
            int len = (int) Math.min(table.sectorSize, out.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(out, off, chunk, 0, len);
            decryptSector(chunk, dk, tweakMask, sector, table.sectorSize);
            System.arraycopy(chunk, 0, out, off, len);
            sector++;
        }
        return out;
    }

    private static byte[] readAll(File f) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(f.length(), 1 << 20));
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        byte[] tmp = new byte[1 << 16];
        int r;
        while ((r = in.read(tmp)) > 0) bos.write(tmp, 0, r);
        in.close();
        return bos.toByteArray();
    }

    // Decrypt every PFS-encrypted file (any size) under appDir in place.
    static int decryptPfsDir(File appDir, byte[] license) throws Exception {
        if (license == null || license.length < 0x60) throw new Exception("licença sem klicensee");
        File sp = new File(appDir, "sce_pfs");
        File filesDbF = new File(sp, "files.db");
        File unicvF = new File(sp, "unicv.db");
        if (!filesDbF.isFile() || !unicvF.isFile()) return 0;
        long fSalt = filesSalt(readAll(filesDbF));
        List<PfsTable> tables = parseUnicv(readAll(unicvF));
        if (tables.isEmpty()) return 0;
        byte[] klicensee = new byte[16];
        System.arraycopy(license, 0x50, klicensee, 0, 16);
        byte[] dk = dataKey(klicensee);
        List<PfsCandidate> candidates = new ArrayList<PfsCandidate>();
        for (PfsTable t : tables) {
            PfsCandidate c = new PfsCandidate();
            c.table = t;
            c.signatureKey = signatureKey(dk, fSalt, t);
            candidates.add(c);
        }
        final int[] done = { 0 };
        walkPfs(appDir, candidates, dk, done);
        return done[0];
    }

    // Decrypt the whole file (any size) in place, streaming in chunks so that
    // multi-gigabyte files (rom/data.psarc) are handled without loading in RAM.
    private static void decryptFileStream(File f, PfsTable table, byte[] dk, byte[] tweakMask) throws Exception {
        long len = f.length();
        long ss = table.sectorSize;
        if (ss == 0) throw new Exception("PFS: sectorSize 0 em " + f.getName());
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        try {
            long chunkFull = (4L << 20) / ss * ss;
            byte[] buf = new byte[(int) Math.min(chunkFull, Math.max(ss, len))];
            long offset = 0;
            long sector = 0;
            while (offset < len) {
                int chunk = (int) Math.min(buf.length, len - offset);
                raf.seek(offset);
                raf.readFully(buf, 0, chunk);
                for (int o = 0; o < chunk; o += (int) ss) {
                    int n = (int) Math.min(ss, chunk - o);
                    byte[] s = java.util.Arrays.copyOfRange(buf, o, o + n);
                    decryptSector(s, dk, tweakMask, sector, ss);
                    System.arraycopy(s, 0, buf, o, n);
                    sector++;
                }
                raf.seek(offset);
                raf.write(buf, 0, chunk);
                offset += chunk;
            }
        } finally {
            raf.close();
        }
    }

    private static void walkPfs(File dir, List<PfsCandidate> candidates, byte[] dk, int[] done) throws Exception {
        File[] children = dir.listFiles();
        if (children == null) return;
        long maxSs = 0;
        for (PfsCandidate c : candidates) maxSs = Math.max(maxSs, c.table.sectorSize);
        for (File f : children) {
            if (f.getName().equals("sce_pfs")) continue;
            if (f.isDirectory()) {
                walkPfs(f, candidates, dk, done);
                continue;
            }
            long len = f.length();
            if (len == 0) continue;
            int headLen = (int) Math.min(len, maxSs);
            byte[] head = new byte[headLen];
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                raf.seek(0);
                raf.readFully(head, 0, headLen);
            } finally {
                raf.close();
            }
            PfsTable table = matchingTable(candidates, len, head);
            if (table == null) continue;
            byte[] tweakMask = hmacSha1(PFS_HMAC_KEY, table.dbseed);
            decryptFileStream(f, table, dk, tweakMask);
            done[0]++;
        }
    }

private PkgExtractor() {}
}