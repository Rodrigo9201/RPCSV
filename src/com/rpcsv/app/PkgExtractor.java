package com.rpcsv.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        readInto(raf, pos, b, len);
        return b;
    }

    private static void readInto(RandomAccessFile raf, long pos, byte[] b, int len) throws Exception {
        raf.seek(pos);
        int got = 0;
        while (got < len) {
            int r = raf.read(b, got, len - got);
            if (r < 0) throw new Exception("pkg truncado em " + (pos + got));
            got += r;
        }
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
        byte[] out = buf.clone();
        new CtrStream(key, iv, startBlock).apply(out, 0, out.length);
        return out;
    }

    /**
     * AES-CTR do pkg com estado entre chunks.
     *
     * <p>O XOR do pkg e' um CTR continuo: o contador sobe de 16 em 16 bytes
     * pelo arquivo inteiro. Antes cada chunk de 64 KB criava um Cipher novo e
     * refazia o keystream desde o inicio do chunk, o que num jogo de 4 GB dava
     * ~65 mil Cipher e 4 arrays por chunk. Aqui o Cipher e' criado uma vez, o
     * keystream avanca em blocos de 16 KB e nada e' alocado por chunk.
     */
    private static final class CtrStream {
        private final Cipher cipher;
        private final byte[] counter;
        private final byte[] keyStream;
        private byte[] keyBuf;
        private int keyPos;

        CtrStream(byte[] key, byte[] iv, long startBlock) throws Exception {
            cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            counter = iv.clone();
            ctrAdd(counter, startBlock);
            keyStream = new byte[16 * 1024];
            keyBuf = new byte[16 * 1024];
            keyPos = keyStream.length;
        }

        void apply(byte[] buf, int off, int len) throws Exception {
            int done = 0;
            while (done < len) {
                if (keyPos >= keyStream.length) refill();
                int n = Math.min(len - done, keyStream.length - keyPos);
                for (int i = 0; i < n; i++) buf[off + done + i] ^= keyStream[keyPos + i];
                keyPos += n;
                done += n;
            }
        }

        private void refill() throws Exception {
            for (int b = 0; b < keyStream.length / 16; b++) {
                System.arraycopy(counter, 0, keyBuf, b * 16, 16);
                ctrAdd(counter, 1);
            }
            cipher.doFinal(keyBuf, 0, keyBuf.length, keyStream, 0);
            keyPos = 0;
        }
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

    /**
     * Todos os campos legiveis do param.sfo, para a tela de informacoes do
     * app. Devolve mapa vazio em vez de lancar: um SFO corrompido nao pode
     * impedir a abertura da tela, so deixa os campos em branco.
     */
    public static Map<String, Object> readParamInfo(String sfoPath) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            byte[] raw = readSfoBytes(sfoPath);
            if (raw == null) return out;
            out.putAll(parseSfo(raw));
        } catch (Exception e) {
            return out;
        }
        return out;
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
        // Sem o flag de dicionario o deflate e' normal: dict fica null e
        // perguntar dict.length estourava NullPointerException, o que fazia
        // qualquer zRIF sem dicionário falhar em vez de decodificar.
        if (dict != null && dict.length > 0) inf.setDictionary(dict);
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

    // ------------------------------------------------------------------
    // Chave/licença
    // ------------------------------------------------------------------

    /** Veredito da validação da licença contra a assinatura PFS do próprio PKG. */
    private static final int LIC_NOT_NEEDED = 0;
    private static final int LIC_VALID = 1;
    /** O PKG tem conteúdo cifrado e nenhuma chave foi informada. */
    private static final int LIC_MISSING = -1;
    /** A chave foi informada mas não serve para este PKG. */
    private static final int LIC_WRONG = -2;

    /** Tamanho máximo aceitável para unicv.db em memória (evita OOM com pkg hostil). */
    private static final int MAX_UNICV_BYTES = 8 << 20;

    /** Um item da tabela do pkg já decifrado. Nada é escrito no disco ao montar a lista. */
    private static final class Item {
        String name;
        long offset;
        long size;
        int flags;
        int pspType;
    }

    /** keyType 0 = pkg público: o conteúdo não é cifrado, então não há o que XOR. */
    private static byte[] maybeDecrypt(byte[] key, byte[] iv, long startBlock, byte[] buf) throws Exception {
        if (key == null) return buf;
        return xorCtr(key, iv, startBlock, buf);
    }

    private static byte[] itemKey(byte[] mainKey, String pkgType, int pspType) {
        boolean pspLike = "psp".equals(pkgType) || "psx".equals(pkgType);
        if (pspLike) return pspType == 0x90 ? mainKey : PKG_PS3_KEY;
        return mainKey;
    }

    /**
     * Lê a tabela de itens e devolve nomes/offsets já decifrados, <b>sem
     * escrever nada</b> no disco.
     *
     * <p>Isolar essa etapa é o que permite recusar uma chave errada antes de
     * criar o diretório do jogo: da versão anterior os arquivos eram gravados
     * primeiro e a chave só era conferida depois (e o erro era engolido), então
     * uma key errada deixava um jogo pela metade na pasta e ainda reportava
     * "instalado".
     */
    private static List<Item> readItems(RandomAccessFile raf, long encOffset, int itemsOffset,
            int itemCount, byte[] mainKey, byte[] iv, String pkgType) throws Exception {
        List<Item> out = new ArrayList<Item>();
        byte[] tab = maybeDecrypt(mainKey, iv, itemsOffset / 16,
                readAt(raf, encOffset + itemsOffset, itemCount * 32));
        for (int i = 0; i < itemCount; i++) {
            byte[] it = new byte[32];
            System.arraycopy(tab, i * 32, it, 0, 32);
            int nameOffset = b32be(it, 0);
            int nameSize = b32be(it, 4);
            Item e = new Item();
            e.offset = b64be(it, 8);
            e.size = b64be(it, 16);
            e.pspType = it[24] & 0xff;
            e.flags = it[27] & 0xff;
            e.name = new String(maybeDecrypt(itemKey(mainKey, pkgType, e.pspType), iv, nameOffset / 16,
                    readAt(raf, encOffset + nameOffset, nameSize)), "UTF-8");
            out.add(e);
        }
        return out;
    }

    /**
     * Decide se a licença (work.bin / zRIF) serve para este PKG.
     *
     * <p>O oráculo é a própria assinatura PFS: o HMAC do primeiro setor de um
     * arquivo cifrado só confere quando o klicensee do rif é o verdadeiro, e
     * esse klicensee sai da chave que o usuário escolheu. Ler unicv.db +
     * files.db + o primeiro setor de cada item custa poucas páginas, então a
     * chave é julgada <b>antes</b> de extrair os gigabytes do jogo.
     *
     * <p>Devolve {@link #LIC_VALID}, {@link #LIC_NOT_NEEDED} (pkg sem
     * conteúdo PFS) ou um veredito negativo para a chave.
     */
    private static int verifyLicense(RandomAccessFile raf, long encOffset, byte[] mainKey,
            byte[] iv, List<Item> items, byte[] rif) throws Exception {
        Item unicv = null, filesDb = null;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if ("sce_pfs/unicv.db".equals(it.name)) unicv = it;
            else if ("sce_pfs/files.db".equals(it.name)) filesDb = it;
        }
        // Sem sce_pfs o conteúdo não é PFS: nada a validar (psp/psm/free).
        if (unicv == null || filesDb == null) return LIC_NOT_NEEDED;
        if (rif == null) return LIC_MISSING;
        if (unicv.size <= 0 || unicv.size > MAX_UNICV_BYTES) return LIC_WRONG;
        if (filesDb.size <= 0 || filesDb.size > MAX_UNICV_BYTES) return LIC_WRONG;

        byte[] unicvBytes = maybeDecrypt(mainKey, iv, unicv.offset / 16,
                readAt(raf, encOffset + unicv.offset, (int) unicv.size));
        byte[] filesDbBytes = maybeDecrypt(mainKey, iv, filesDb.offset / 16,
                readAt(raf, encOffset + filesDb.offset, (int) filesDb.size));

        long fSalt = filesSalt(filesDbBytes);
        List<PfsTable> tables = parseUnicv(unicvBytes);
        // unicv.db sem tabela legível = nada cifrado por PFS neste pkg.
        if (tables.isEmpty()) return LIC_NOT_NEEDED;

        if (rif.length < 0x60) return LIC_WRONG;
        byte[] klicensee = new byte[16];
        System.arraycopy(rif, 0x50, klicensee, 0, 16);
        byte[] dk = dataKey(klicensee);

        List<PfsCandidate> candidates = new ArrayList<PfsCandidate>();
        long maxSs = 0;
        for (int i = 0; i < tables.size(); i++) {
            PfsTable t = tables.get(i);
            PfsCandidate c = new PfsCandidate();
            c.table = t;
            c.signatureKey = signatureKey(dk, fSalt, t);
            candidates.add(c);
            if (t.sectorSize > maxSs) maxSs = t.sectorSize;
        }
        if (maxSs <= 0 || maxSs > MAX_UNICV_BYTES) return LIC_WRONG;

        // Um único setor por item já basta: matchingTable compara o HMAC do
        // cabeçalho com a assinatura guardada em unicv.db.
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.size == 0) continue;
            if (it.name.startsWith("sce_pfs/")) continue;
            int headLen = (int) Math.min(it.size, maxSs);
            byte[] head = maybeDecrypt(mainKey, iv, it.offset / 16,
                    readAt(raf, encOffset + it.offset, headLen));
            if (matchingTable(candidates, it.size, head) != null) return LIC_VALID;
        }
        // Havia PFS, havia chave, e nada casou: a chave não é deste PKG.
        return LIC_WRONG;
    }

    /** Extracts a PKG into baseDir/ux0/app/<TITLEID>/ (or pspemu/...). */
    /** Recebe o andamento da instalação para a barra de progresso. */
    public interface ProgressListener {
        /**
         * @param phase "verify" | "extract" | "finalize"
         * @param done  bytes concluídos na fase
         * @param total bytes estimados da fase (0 se desconhecido)
         */
        void onProgress(String phase, long done, long total);
    }

    public static Map<String, Object> install(String pkgPath, String zrifText, String workbinPath, String baseDir) throws Exception {
        return install(pkgPath, zrifText, workbinPath, baseDir, null);
    }

    public static Map<String, Object> install(String pkgPath, String zrifText, String workbinPath, String baseDir,
                                              ProgressListener progress) throws Exception {
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

            // keyType 0 = pkg público/livre: o conteúdo não é cifrado, e a
            // versão anterior estourava "chave de pkg não suportada: 0" nele,
            // o que impedia instalar qualquer jogo grátis.
            byte[] mainKey;
            if (keyType == 0) mainKey = null;
            else if (keyType == 1) mainKey = PKG_PSP_KEY.clone();
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
            } else if (!contentId.isEmpty() && contentId.length() >= 16) {
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

            // Tabela de itens lida em memória: nada foi escrito no disco até aqui.
            List<Item> items = readItems(raf, encOffset, itemsOffset, itemCount, mainKey, iv, pkgType);

            // Chave errada BLOQUEIA a instalação, e ela é julgada antes de
            // extrair qualquer arquivo. Sem esta checagem o fluxo antigo
            // gravava o jogo inteiro, engolia a falha da PFS e devolvia
            // ok=true — o usuário só descobria que a key estava errada quando
            // o jogo não abria.
            if (progress != null) progress.onProgress("verify", 0, 1);
            int lic = "psm".equals(pkgType) ? LIC_NOT_NEEDED
                    : verifyLicense(raf, encOffset, mainKey, iv, items, rif);
            if (progress != null) progress.onProgress("verify", 1, 1);
            if (lic == LIC_MISSING) {
                throw new Exception("Este PKG é protegido: informe a chave (zRIF ou work.bin) correta para instalar.");
            }
            if (lic == LIC_WRONG) {
                throw new Exception("Chave inválida para este PKG. A instalação foi cancelada — selecione a chave correta.");
            }
            boolean rifOk = lic == LIC_VALID;

            String rootName;
            if ("app".equals(pkgType) || "psm".equals(pkgType)) rootName = "ux0/app/" + id;
            else if ("dlc".equals(pkgType)) rootName = "ux0/addcont/" + id + "/" + (contentId.length() >= 24 ? contentId.substring(16, 24) : "content");
            else rootName = "pspemu/PSP/GAME/" + id;

            File baseDirF = new File(baseDir, rootName);
            // Total conhecido antes de gravar qualquer byte: e a soma dos
            // itens + head/tail. Sem isso a barra so cresceria por contagem de
            // arquivo e ficaria presa em 99% com um jogo de um arquivo so.
            long totalBytes = encOffset + itemsSize + Math.max(0, raf.length() - (encOffset + encSize));
            if (progress != null) progress.onProgress("extract", 0, totalBytes);
            long doneBytes = 0;
            // Um buffer e um keystream para o jogo inteiro: antes cada chunk de
            // 64 KB alocava 4 arrays e criava um Cipher, o que em um jogo de
            // 4 GB significava centenas de milhares de alocacoes.
            final int chunkSize = 262144;
            byte[] buf = new byte[chunkSize];
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                if (it.name.startsWith("/") || it.name.contains("..")) continue;

                if (it.flags == 4 || it.flags == 18) {
                    new File(baseDirF, it.name).mkdirs();
                    continue;
                }

                boolean decrypt = !(("app".equals(pkgType) || "dlc".equals(pkgType)) && "sce_sys/package/digs.bin".equals(it.name));
                File out = new File(baseDirF, it.name);
                if (out.getParentFile() != null) out.getParentFile().mkdirs();
                FileOutputStream os = new FileOutputStream(out);
                try {
                    byte[] key = itemKey(mainKey, pkgType, it.pspType);
                    CtrStream ctr = (decrypt && key != null) ? new CtrStream(key, iv, it.offset / 16) : null;
                    long offset = it.offset;
                    long remain = it.size;
                    while (remain > 0) {
                        int chunk = (int) Math.min(remain, chunkSize);
                        readInto(raf, encOffset + offset, buf, chunk);
                        if (ctr != null) ctr.apply(buf, 0, chunk);
                        os.write(buf, 0, chunk);
                        offset += chunk;
                        remain -= chunk;
                        // A cada 4 MB: menos IPC por arquivo minúsculo, e ainda
                        // Updates fluido num jogo de varios GB.
                        if (progress != null && (doneBytes - (doneBytes / (4L << 20)) * (4L << 20)) >= (4L << 20)) {
                            progress.onProgress("extract", doneBytes, totalBytes);
                        }
                        doneBytes += chunk;
                    }
                } finally {
                    os.close();
                }
            }
            if (progress != null) progress.onProgress("extract", totalBytes, totalBytes);

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
                // work.bin só é gravado com chave validada (ou quando o pkg
                // nem usa PFS). Gravar uma chave não conferida aqui é o que
                // produzia um jogo que o emulador recusava depois.
                if (rif != null && (rifOk || lic == LIC_NOT_NEEDED)) {
                    writeTo(new File(pkgDir, "work.bin"), rif);
                }
            }

            // A licença também precisa ir para vita/ux0/license/<titleId>/
            // <contentId>.rif. O sce_sys/package/work.bin sozinho não serve:
            // é a engine quem lê o .rif (get_license) para tirar o klic, e
            // sem esse arquivo ela cai no valor default — klic zerado — e
            // decrypt_fself() aborta com "No klic provided for encrypted App".
            // Aí a sessão nem chega a comecar e a activity fecha em menos de
            // 1 s, que é exatamente o "crash ao iniciar o jogo".
            String licencePath = null;
            if (rif != null && (rifOk || lic == LIC_NOT_NEEDED)
                    && ("app".equals(pkgType) || "psm".equals(pkgType))) {
                try {
                    String rifCid = rifContentId(rif);
                    if (rifCid.isEmpty()) rifCid = contentId;
                    if (rifCid.length() > 16) {
                        File licDir = new File(baseDir, "ux0/license/" + id);
                        File licDst = new File(licDir, rifCid + ".rif");
                        writeTo(licDst, rif);
                        licencePath = licDst.getAbsolutePath();
                    }
                } catch (Exception e) {
                    licencePath = null;
                }
            }

            // PFS: descriptografa o conteúdo selado em sce_pfs. Só roda com a
            // chave já validada acima, então "instalado" passou a significar
            // "pronto para abrir" e não "os arquivos foram copiados".
            int pfsFiles = 0;
            String pfsError = null;
            if (rifOk && new File(baseDirF, "sce_pfs").exists()) {
                try {
                    if (progress != null) progress.onProgress("finalize", 0, 0);
                    pfsFiles = decryptPfsDir(baseDirF, rif);
                    if (progress != null) progress.onProgress("finalize", 1, 1);
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
            r.put("licenceChecked", Boolean.valueOf(rifOk));
            if (licencePath != null) r.put("licenceInstalled", licencePath);
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

    /**
     * CONTENT_ID que a própria licença carrega (SceNpDrmLicense.content_id,
     * offset 0x10, 0x30 bytes, terminado em NUL). É esse nome que a engine usa
     * para montar o caminho do .rif, então vale mais do que o do param.sfo.
     */
    private static String rifContentId(byte[] rif) {
        if (rif == null || rif.length < 0x40) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0x10; i < 0x40 && i < rif.length; i++) {
            int c = rif[i] & 0xFF;
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString().trim();
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
        new PfsCiphers(key, sectorSize).sector(bytes, 0, bytes.length, tweakMask, sector, sectorSize);
    }

    /**
     * Ciphers de PFS mantidos vivos durante um arquivo inteiro.
     *
     * <p>Num arquivo selado so o IV muda de setor para setor, mas a versao
     * anterior criava dois Cipher (CBC + ECB) e um array por setor: um
     * data.psarc de 2 GB sao 65 mil setores, ou seja, 130 milCipher e 65 mil
     * copias so de overhead. Aqui os Cipher nascem uma vez e os setores sao
     * decifrados no lugar, direto no buffer do chunk.
     */
    private static final class PfsCiphers {
        private final SecretKeySpec spec;
        private final Cipher cbc = Cipher.getInstance("AES/CBC/NoPadding");
        private final Cipher ecb;
        private final byte[] scratch;
        private final byte[] iv = new byte[16];
        private final byte[] one = new byte[16];

        PfsCiphers(byte[] key, long sectorSize) throws Exception {
            spec = new SecretKeySpec(key, "AES");
            ecb = Cipher.getInstance("AES/ECB/NoPadding");
            ecb.init(Cipher.ENCRYPT_MODE, spec);
            scratch = new byte[(int) Math.max(16, Math.min(sectorSize, 1L << 20))];
        }

        void sector(byte[] bytes, int off, int len, byte[] tweakMask, long sector, long sectorSize)
                throws Exception {
            if (len <= 0) return;
            long byteOffset = sector * sectorSize;
            System.arraycopy(tweakMask, 0, iv, 0, 16);
            for (int i = 0; i < 8; i++) iv[i] ^= (byte) (byteOffset >>> (i * 8));

            int aligned = len & ~0xf;
            byte[] tailIv = null;
            if (aligned != 0 && aligned != len) {
                tailIv = java.util.Arrays.copyOfRange(bytes, off + aligned - 16, off + aligned);
            }
            if (aligned != 0) {
                cbc.init(Cipher.DECRYPT_MODE, spec, new IvParameterSpec(iv));
                cbc.doFinal(bytes, off, aligned, scratch, 0);
                System.arraycopy(scratch, 0, bytes, off, aligned);
            }
            if (aligned != len) {
                // O resto (< 16 bytes) e' a cifra de um unico bloco cujo IV e' o
                // bloco anterior do ciphertext, entao vai por ECB.
                byte[] src = tailIv != null ? tailIv : iv;
                System.arraycopy(src, 0, one, 0, 16);
                byte[] encIv = ecb.doFinal(one);
                for (int i = 0; i < len - aligned; i++) {
                    bytes[off + aligned + i] ^= encIv[i];
                }
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
        Set<String> plain = pflistUnencrypted(new File(sp, "pflist"));
        walkPfs(appDir, candidates, dk, done, plain, "");
        return done[0];
    }

    // pflist marca com "nenc" os arquivos que o PFS indexa mas o PKG guarda em
    // claro: sce_sys/param.sfo e sce_sys/clearsign, no Taiko. A assinatura do
    // primeiro setor deles tambem confere (o indice registra o arquivo em
    // claro), entao a busca por tabela nao os distingue e a descriptografia
    // os corrompia. No Taiko o param.sfo por ca disso virava lixo
    // (md5 c66cf6fc) e o clearsign perdia o cabecalho .DRM. A flag e a unica
    // fonte que separa os dois casos, e e o que o Vita3K respeita.
    private static Set<String> pflistUnencrypted(File pflist) throws Exception {
        Set<String> out = new HashSet<String>();
        if (pflist == null || !pflist.isFile()) return out;
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(pflist), "UTF-8"));
        try {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] f = line.split("\t+", -1);
                if (f.length >= 3 && f[2].trim().equals("nenc")) out.add(f[0].trim());
            }
        } finally {
            br.close();
        }
        return out;
    }

    // Decrypt the whole file (any size) in place, streaming in chunks so that
    // multi-gigabyte files (rom/data.psarc) are handled without loading in RAM.
    private static void decryptFileStream(File f, PfsTable table, byte[] dk, byte[] tweakMask) throws Exception {
        long len = f.length();
        long ss = table.sectorSize;
        if (ss == 0) throw new Exception("PFS: sectorSize 0 em " + f.getName());
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        try {
            PfsCiphers ciphers = new PfsCiphers(dk, ss);
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
                    ciphers.sector(buf, o, n, tweakMask, sector, ss);
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

    private static void walkPfs(File dir, List<PfsCandidate> candidates, byte[] dk, int[] done,
                                Set<String> plain, String rel) throws Exception {
        File[] children = dir.listFiles();
        if (children == null) return;
        long maxSs = 0;
        for (PfsCandidate c : candidates) maxSs = Math.max(maxSs, c.table.sectorSize);
        for (File f : children) {
            if (f.getName().equals("sce_pfs")) continue;
            String path = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) {
                walkPfs(f, candidates, dk, done, plain, path);
                continue;
            }
            long len = f.length();
            if (len == 0) continue;
            if (plain.contains(path)) continue;
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