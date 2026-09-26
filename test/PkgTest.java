import com.rpcsv.app.PkgExtractor;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Harness de desktop para o PkgExtractor: monta PKGs sintéticos e confere os
 * quatro caminhos que o app precisa acertar.
 *
 *   1. pkg público (keyType 0) instala sem chave
 *   2. pkg cifrado sem chave -> BLOQUEIA
 *   3. pkg cifrado com chave errada -> BLOQUEIA
 *   4. pkg cifrado com a chave certa -> CONCLUÍ e descriptografa o PFS
 */
public final class PkgTest {

    // mesmas constantes de PkgExtractor
    static final byte[] PFS_CONTRACT_KEY = fromHex("e12213b48016b0e99ab81f8ec02ad4a2");
    static final byte[] PFS_HMAC_KEY = fromHex("e462258b1f3121560745db62b1436723d2bf80fe");
    static final byte[] PFS_SECRET_HMAC_KEY = fromHex("afe656bb3c17256a3c809f6e9bf19fdd5a388543");
    static final byte[] PFS_SECRET_IV = fromHex("74d20cc39881c213ee770b1010e4bea7");
    static final int PFS_UNICV_TABLE_SIZE = 72;

    static byte[] fromHex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++)
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    static void be32(ByteArrayOutputStream o, int v) {
        o.write((v >>> 24) & 0xff); o.write((v >>> 16) & 0xff);
        o.write((v >>> 8) & 0xff); o.write(v & 0xff);
    }

    static void be64(ByteArrayOutputStream o, long v) {
        for (int i = 7; i >= 0; i--) o.write((int) ((v >>> (i * 8)) & 0xff));
    }

    static byte[] aes(boolean enc, byte[] key, byte[] iv, byte[] data, boolean cbc) throws Exception {
        String t = cbc ? "AES/CBC/NoPadding" : "AES/ECB/NoPadding";
        Cipher c = Cipher.getInstance(t);
        int mode = enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE;
        if (cbc) c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        else c.init(mode, new SecretKeySpec(key, "AES"));
        return c.doFinal(data);
    }

    static byte[] hmac(byte[] key, byte[] msg) throws Exception {
        Mac m = Mac.getInstance("HmacSHA1");
        m.init(new SecretKeySpec(key, "HmacSHA1"));
        return m.doFinal(msg);
    }

    static byte[] le32(long n) {
        return new byte[]{(byte) n, (byte) (n >>> 8), (byte) (n >>> 16), (byte) (n >>> 24)};
    }

    // ------------------------------------------------------------ PFS
    static byte[] pfsDataKey(byte[] klicensee) throws Exception {
        return aes(false, PFS_CONTRACT_KEY, null, klicensee, false);
    }

    static byte[] pfsSignatureKey(byte[] dk, long fSalt, long tblSalt) throws Exception {
        byte[] salt8 = new byte[8];
        System.arraycopy(le32(fSalt), 0, salt8, 0, 4);
        System.arraycopy(le32(tblSalt), 0, salt8, 4, 4);
        byte[] base = hmac(PFS_SECRET_HMAC_KEY, salt8);
        byte[] secret = new byte[20];
        byte[] blk = aes(true, dk, PFS_SECRET_IV, java.util.Arrays.copyOfRange(base, 0, 16), true);
        System.arraycopy(blk, 0, secret, 0, 16);
        byte[] enc = aes(true, dk, null, java.util.Arrays.copyOfRange(secret, 0, 16), false);
        for (int i = 0; i < 4; i++) secret[16 + i] = (byte) (base[16 + i] ^ enc[i]);
        return hmac(secret, le32(0));
    }

    static byte[] pfsEncrypt(byte[] plain, byte[] dbseed, byte[] dk, long sectorSize) throws Exception {
        byte[] tweakMask = hmac(PFS_HMAC_KEY, dbseed);
        byte[] out = plain.clone();
        long sector = 0;
        for (int off = 0; off < out.length; off += (int) sectorSize) {
            int len = (int) Math.min(sectorSize, out.length - off);
            byte[] chunk = java.util.Arrays.copyOfRange(out, off, off + len);
            byte[] iv = new byte[16];
            System.arraycopy(tweakMask, 0, iv, 0, 16);
            long byteOffset = sector * sectorSize;
            for (int i = 0; i < 8; i++) iv[i] ^= (byte) (byteOffset >>> (i * 8));
            int aligned = len & ~0xf;
            if (aligned != 0)
                System.arraycopy(aes(true, dk, iv, java.util.Arrays.copyOf(chunk, aligned), true), 0, chunk, 0, aligned);
            if (aligned != len) {
                byte[] src = aligned != 0 ? java.util.Arrays.copyOfRange(chunk, aligned - 16, aligned) : iv;
                byte[] encIv = aes(true, dk, null, src, false);
                for (int i = 0; i < len - aligned; i++) chunk[aligned + i] ^= encIv[i];
            }
            System.arraycopy(chunk, 0, out, off, len);
            sector++;
        }
        return out;
    }

    // --------------------------------------------------------- montagem
    static final class Entry {
        String name; byte[] data; int flags;
        Entry(String n, byte[] d) { this(n, d, 0); }
        Entry(String n, byte[] d, int f) { name = n; data = d; flags = f; }
    }

    /**
     * Monta um PKG. keyType 0 deixa tudo em claro; keyType != 0 cifra com a
     * chave derivada do IV, como o Vita3K/pkg2zip fazem.
     */
    static File buildPkg(File out, int contentType, int keyType, String titleId,
                         String title, List<Entry> entries, byte[] klicensee) throws Exception {
        byte[] iv = new byte[16];
        for (int i = 0; i < 16; i++) iv[i] = (byte) (0xA0 + i);

        // 1) nomes: nameOffset e relativo a encOffset e o keystream CTR e
        //    posicionado em nameOffset/16, entao cada nome comeca num bloco
        //    de 16 bytes (como num pkg real).
        // No layout real do pkg a regiao cifrada e: [tabela de itens][nomes][dados]
        int itemsRegionLen = align16(entries.size() * 32);
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        int[] nameOff = new int[entries.size()];
        int[] nameLen = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            byte[] nb = e.name.getBytes("UTF-8");
            nameOff[i] = itemsRegionLen + names.size();
            nameLen[i] = nb.length;
            names.write(nb);
            while (names.size() % 16 != 0) names.write(0);
        }
        byte[] nameBytes = names.toByteArray();

        // 2) tabela de itens
        ByteArrayOutputStream items = new ByteArrayOutputStream();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            be32(items, nameOff[i]);
            be32(items, nameLen[i]);
            be64(items, 0);                 // dataOffset, preenchido abaixo
            be64(items, e.data.length);
            items.write(0); items.write(0); items.write(0);
            items.write(e.flags);
            items.write(0); items.write(0); items.write(0); items.write(0);
        }
        byte[] itemBytes = items.toByteArray();

        // 3) dataOffset relativo a encOffset, depois da maior das duas areas
        int dataStart = align16(itemsRegionLen + nameBytes.length);
        int[] dataOff = new int[entries.size()];
        int cursor = dataStart;
        for (int i = 0; i < entries.size(); i++) {
            dataOff[i] = cursor;
            cursor = align16(cursor + entries.get(i).data.length);
        }
        ByteArrayOutputStream items2 = new ByteArrayOutputStream();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            be32(items2, nameOff[i]);
            be32(items2, nameLen[i]);
            be64(items2, dataOff[i]);
            be64(items2, e.data.length);
            items2.write(0); items2.write(0); items2.write(0);
            items2.write(e.flags);
            items2.write(0); items2.write(0); items2.write(0); items2.write(0);
        }
        itemBytes = items2.toByteArray();

        // SFO vai na regiao plaintext do cabecalho, como num pkg real
        byte[] sfo = buildSfo(titleId, title);

        int metaCount = 3;
        int metaOffset = 512;
        int blk2 = 12, blk13 = 16, blk14 = 16;
        int metaSize = blk2 + blk13 + blk14;
        int sfoAbs = align16(metaOffset + metaSize);
        int encOffset = align16(sfoAbs + sfo.length);

        int itemsOffset = 0;
        int bodyLen = cursor;
        byte[] body = new byte[bodyLen];
        System.arraycopy(itemBytes, 0, body, itemsOffset, itemBytes.length);
        System.arraycopy(nameBytes, 0, body, itemsRegionLen, nameBytes.length);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            System.arraycopy(e.data, 0, body, dataOff[i], e.data.length);
        }

        if (keyType != 0) {
            byte[] mainKey = mainKeyFor(keyType, iv);
            body = xorCtrLocal(mainKey, iv, 0, body);
        }

        byte[] h = new byte[512];
        writeBe32(h, 0, 0x7f504b47);
        writeBe32(h, 8, metaOffset);
        writeBe32(h, 12, metaCount);
        writeBe32(h, 20, entries.size());
        writeBe64(h, 32, encOffset);
        writeBe64(h, 40, bodyLen);
        System.arraycopy(iv, 0, h, 0x70, 16);
        writeBe32(h, 192, 0x7f657874);
        h[0xE7] = (byte) (keyType & 7);

        byte[] meta = new byte[metaSize];
        int m = 0;
        writeBe32(meta, m, 2); writeBe32(meta, m + 4, 4); writeBe32(meta, m + 8, contentType); m += blk2;
        writeBe32(meta, m, 13); writeBe32(meta, m + 4, 8);
        writeBe32(meta, m + 8, itemsOffset); writeBe32(meta, m + 12, itemBytes.length); m += blk13;
        writeBe32(meta, m, 14); writeBe32(meta, m + 4, 8);
        writeBe32(meta, m + 8, sfoAbs); writeBe32(meta, m + 12, sfo.length); m += blk14;

        RandomAccessFile f = new RandomAccessFile(out, "rw");
        try {
            f.setLength(0);
            f.write(h);
            f.seek(metaOffset);
            f.write(meta);
            f.seek(sfoAbs);
            f.write(sfo);
            f.seek(encOffset);
            f.write(body);
        } finally {
            f.close();
        }
        return out;
    }

    static int align16(int v) { return (v + 15) / 16 * 16; }

    static void writeBe32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
    }

    static void writeBe64(byte[] b, int o, long v) {
        for (int i = 0; i < 8; i++) b[o + i] = (byte) (v >>> ((7 - i) * 8));
    }

    static final byte[] K1 = fromHex("07f2c68290b50d2c33818d709b60e62b");
    static final byte[] K2 = fromHex("e31a70c9ce1dd72bf3c0622963f2eccb");
    static final byte[] K3 = fromHex("423aca3a2bd5649f9686abad6fd8801f");
    static final byte[] K4 = fromHex("af07fd59652527baf13389668b17d9ea");

    static byte[] mainKeyFor(int keyType, byte[] iv) throws Exception {
        switch (keyType) {
            case 1: return K1.clone();
            case 2: return aes(true, K2, null, iv, false);
            case 3: return aes(true, K3, null, iv, false);
            case 4: return aes(true, K4, null, iv, false);
            default: return null;
        }
    }

    static byte[] xorCtrLocal(byte[] key, byte[] iv, long startBlock, byte[] buf) throws Exception {
        if (key == null) return buf;
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
        byte[] ks = aes(true, key, null, inb, false);
        byte[] out = buf.clone();
        for (int i = 0; i < n; i++) out[i] ^= ks[i];
        return out;
    }

    static void ctrAdd(byte[] ct, long n) {
        long carry = n;
        for (int i = 15; carry != 0 && i >= 0; i--) {
            long v = (ct[i] & 0xffL) + (carry & 0xffL);
            ct[i] = (byte) (v & 0xff);
            carry = (carry >>> 8) + (v >> 8);
        }
    }

    static byte[] buildSfo(String titleId, String title) throws Exception {
        String[] keys = {"CONTENT_ID", "TITLE", "TITLE_ID"};
        String[] vals = {"UP0002-" + titleId + "_00-0000000000000000", title, titleId};
        ByteArrayOutputStream keyB = new ByteArrayOutputStream();
        ByteArrayOutputStream valB = new ByteArrayOutputStream();
        int[] keyOff = new int[keys.length];
        int[] valOff = new int[vals.length];
        for (int i = 0; i < keys.length; i++) {
            keyOff[i] = keyB.size();
            byte[] kb = keys[i].getBytes("UTF-8");
            keyB.write(kb); keyB.write(0);
        }
        for (int i = 0; i < vals.length; i++) {
            valOff[i] = valB.size();
            byte[] vb = vals[i].getBytes("UTF-8");
            valB.write(vb); valB.write(0);
        }
        int keysBase = 20 + keys.length * 16;
        int valsBase = keysBase + keyB.size();
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        // O SFO e todo little-endian (parseSfo usa b32le/b16le) e o magic em
        // disco e "\0PSF".
        byte[] hh = new byte[20];
        hh[0] = 0x00; hh[1] = 0x50; hh[2] = 0x53; hh[3] = 0x46;
        le32(hh, 8, keysBase);     // offset absoluto das chaves
        le32(hh, 12, valsBase);    // offset absoluto dos valores
        le32(hh, 16, keys.length); // contagem
        s.write(hh);
        for (int i = 0; i < keys.length; i++) {
            byte[] e = new byte[16];
            e[0] = (byte) keyOff[i];
            e[1] = (byte) (keyOff[i] >>> 8);
            e[4] = 0x04; e[5] = 0x02;      // 0x0204 = string UTF-8 (0x0404 seria int32)
            le32(e, 12, valOff[i]);
            s.write(e);
        }
        s.write(keyB.toByteArray());
        s.write(valB.toByteArray());
        return s.toByteArray();
    }

    static void le32(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >>> 8); b[o + 2] = (byte) (v >>> 16); b[o + 3] = (byte) (v >>> 24);
    }

    // ------------------------------------------------------------- main
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + "  -> " + detail); }
    }

    public static void main(String[] args) throws Exception {
        // Saida temporaria: os PKGs sinteticos ficam aqui so para o teste.
        File work = new File(System.getProperty("rpcsv.test.work", "/tmp/rpcsv-pkgtest"));
        deleteRec(work);
        work.mkdirs();

        final String TID = "PCSF00001";
        final byte[] GOOD_KLIC = fromHex("00112233445566778899aabbccddeeff");
        final byte[] BAD_KLIC = fromHex("ffeeddccbbaa99887766554433221100");

        // payload PFS-cifrado
        byte[] plain = new byte[8192];
        for (int i = 0; i < plain.length; i++) plain[i] = (byte) ("RPCAUDIODATA".charAt(i % 12));
        long sectorSize = 4096;
        byte[] dbseed = new byte[20];
        for (int i = 0; i < 20; i++) dbseed[i] = (byte) (0x30 + i);
        byte[] dk = pfsDataKey(GOOD_KLIC);
        byte[] sealed = pfsEncrypt(plain, dbseed, dk, sectorSize);

        // unicv.db com uma tabela
        long pageSize = 0x1000, maxSignatures = 1, sectors = 2;
        byte[] sk = pfsSignatureKey(dk, 7, 1);
        byte[] firstSig = hmac(sk, java.util.Arrays.copyOf(sealed, (int) sectorSize));
        // 4 paginas: a tabela fica na pag 1 e a assinatura cai na pag 3 (signaturePage)
        byte[] unicv = new byte[(int) (pageSize * 4)];
        System.arraycopy("SCEIRODB".getBytes("ISO-8859-1"), 0, unicv, 0, 8);
        le32(unicv, 12, (int) pageSize);
        le32(unicv, 24, (int) (pageSize * 3));   // dataSize -> dataEnd = pageSize*4
        int tbl = (int) pageSize;
        System.arraycopy("SCEIFTBL".getBytes("ISO-8859-1"), 0, unicv, tbl, 8);
        le32(unicv, tbl + 8, 2);
        le32(unicv, tbl + 12, (int) pageSize);
        le32(unicv, tbl + 16, (int) maxSignatures);
        le32(unicv, tbl + 20, (int) sectors);
        le32(unicv, tbl + 24, (int) sectorSize);
        System.arraycopy(dbseed, 0, unicv, tbl + 52, 20);
        int sigPage = (int) (pageSize * 2);
        System.arraycopy(firstSig, 0, unicv, sigPage + 16, 20);

        // files.db só precisa do cabeçalho com o salt
        byte[] filesDb = new byte[64];
        System.arraycopy("SCENGPFS".getBytes("ISO-8859-1"), 0, filesDb, 0, 8);
        le32(filesDb, 28, 7);   // filesSalt e lido por asUnsigned

        File rifOk = new File(work, "good.bin");
        FileOutputStream o1 = new FileOutputStream(rifOk);
        o1.write(new byte[0x50]);
        o1.write(GOOD_KLIC);
        byte[] pad = new byte[512 - 0x50 - 16];
        o1.write(pad);
        o1.close();

        File rifBad = new File(work, "bad.bin");
        FileOutputStream o2 = new FileOutputStream(rifBad);
        o2.write(new byte[0x50]);
        o2.write(BAD_KLIC);
        o2.write(pad);
        o2.close();

        // ---------------- 1. pkg público (keyType 0), sem chave ----------
        System.out.println("[1] pkg publico (keyType 0), sem chave");
        File f1 = new File(work, "free.pkg");
        buildPkg(f1, 0x15, 0, TID, "Jogo Gratis", list(
                new Entry("sce_sys/param.sfo", buildSfo(TID, "Jogo Gratis")),
                new Entry("sce_sys/icon0.png", png(64)),
                new Entry("eboot.bin", "FAKE_EBOOT_FREE".getBytes("UTF-8"))), null);
        File base1 = new File(work, "base1");
        try {
            Map<String, Object> r = PkgExtractor.install(f1.getPath(), "", "", base1.getPath());
            boolean ok = Boolean.TRUE.equals(r.get("ok"));
            check("keyType 0 instala sem chave", ok, String.valueOf(r));
            check("eboot.bin extraido", new File(base1, "ux0/app/" + TID + "/eboot.bin").isFile(), "faltou eboot");
            check("icon0.png extraido (logo)", new File(base1, "ux0/app/" + TID + "/sce_sys/icon0.png").isFile(), "faltou icon0");
            check("param.sfo extraido (nome)", new File(base1, "ux0/app/" + TID + "/sce_sys/param.sfo").isFile(), "faltou sfo");
        } catch (Exception e) {
            check("keyType 0 instala sem chave", false, e.getMessage());
        }

        // ---------------- 2. pkg cifrado sem chave ----------------------
        System.out.println("[2] pkg cifrado, SEM chave -> deve bloquear");
        File f2 = new File(work, "locked.pkg");
        buildPkg(f2, 0x15, 2, TID, "Jogo Pago", list(
                new Entry("sce_sys/param.sfo", buildSfo(TID, "Jogo Pago")),
                new Entry("sce_sys/icon0.png", png(64)),
                new Entry("sce_pfs/files.db", filesDb),
                new Entry("sce_pfs/unicv.db", unicv),
                new Entry("data.psarc", sealed)), null);
        File base2 = new File(work, "base2");
        try {
            Map<String, Object> r = PkgExtractor.install(f2.getPath(), "", "", base2.getPath());
            check("sem chave deve bloquear", false, "instalou mesmo assim: " + r.get("titleId"));
        } catch (Exception e) {
            boolean blocked = e.getMessage() != null && e.getMessage().toLowerCase().contains("chave");
            check("sem chave -> erro de chave", blocked, e.getMessage());
        }
        check("nada gravado sem chave", !new File(base2, "ux0/app/" + TID + "/data.psarc").exists(),
                "deixou arquivo para tras");

        // ---------------- 3. chave errada -------------------------------
        System.out.println("[3] chave ERRADA -> deve bloquear");
        File base3 = new File(work, "base3");
        try {
            Map<String, Object> r = PkgExtractor.install(f2.getPath(), "", rifBad.getPath(), base3.getPath());
            check("chave errada deve bloquear", false, "instalou mesmo assim: " + r.get("titleId"));
        } catch (Exception e) {
            boolean blocked = e.getMessage() != null && e.getMessage().toLowerCase().contains("chave");
            check("chave errada -> erro de chave", blocked, e.getMessage());
        }
        check("nada gravado com chave errada", !new File(base3, "ux0/app/" + TID + "/data.psarc").exists(),
                "deixou arquivo para tras");

        // ---------------- 4. chave certa --------------------------------
        System.out.println("[4] chave CERTA -> deve concluir");
        File base4 = new File(work, "base4");
        try {
            Map<String, Object> r = PkgExtractor.install(f2.getPath(), "", rifOk.getPath(), base4.getPath());
            check("chave certa conclui", Boolean.TRUE.equals(r.get("ok")), String.valueOf(r));
            check("licenceChecked=true", Boolean.TRUE.equals(r.get("licenceChecked")), String.valueOf(r.get("licenceChecked")));
            File dir = new File(base4, "ux0/app/" + TID);
            check("data.psarc presente", new File(dir, "data.psarc").isFile(), "faltou psarc");
            check("work.bin gravado", new File(dir, "sce_sys/package/work.bin").isFile(), "faltou work.bin");
            byte[] got = Files.readAllBytes(new File(dir, "data.psarc").toPath());
            check("PFS descriptografado (conteudo igual ao original)",
                    java.util.Arrays.equals(got, plain),
                    "primeiros bytes=" + hex(java.util.Arrays.copyOf(got, 12)));
            check("nome do jogo lido do sfo", "Jogo Pago".equals(String.valueOf(r.get("title"))),
                    "title=" + r.get("title"));
            check("titleId correto", TID.equals(String.valueOf(r.get("titleId"))),
                    "titleId=" + r.get("titleId"));
        } catch (Exception e) {
            e.printStackTrace();
            check("chave certa conclui", false, e.toString());
        }

        // ---------------- 5. a licenca vai para ux0/license/<titleId>/ -----
        // O work.bin sozinho nao resolve: a engine le o .rif por
        // vita/ux0/license/<titleId>/<contentId>.rif (get_license) para tirar
        // o klic. Sem esse arquivo o jogo abre com klic zerado e morre em
        // "No klic provided for encrypted App".
        System.out.println("[5] licenca gravada em ux0/license/<titleId>/<contentId>.rif");
        final String RIF_CID = "UP0177-" + TID + "_00-PJDF2MOMJTNSDAYO";
        byte[] rifCidBytes = new byte[512];
        byte[] cid = RIF_CID.getBytes("ISO-8859-1");
        System.arraycopy(cid, 0, rifCidBytes, 0x10, cid.length);   // content_id
        System.arraycopy(GOOD_KLIC, 0, rifCidBytes, 0x50, 16);     // klicensee
        File rifWithCid = new File(work, "good-cid.bin");
        FileOutputStream oc = new FileOutputStream(rifWithCid);
        oc.write(rifCidBytes);
        oc.close();

        File fLic = new File(work, "lic.pkg");
        buildPkg(fLic, 0x15, 2, TID, "Jogo Licenciado", list(
                new Entry("sce_sys/param.sfo", buildSfo(TID, "Jogo Licenciado")),
                new Entry("sce_pfs/files.db", filesDb),
                new Entry("sce_pfs/unicv.db", unicv),
                new Entry("data.psarc", sealed)), null);
        File baseLic = new File(work, "baseLic");
        try {
            Map<String, Object> r = PkgExtractor.install(fLic.getPath(), "", rifWithCid.getPath(), baseLic.getPath());
            check("pkg licenciado conclui", Boolean.TRUE.equals(r.get("ok")), String.valueOf(r));
            File rifDst = new File(baseLic, "ux0/license/" + TID + "/" + RIF_CID + ".rif");
            check("licenceInstalled apontado no resultado", r.get("licenceInstalled") != null,
                    "resultado=" + r.get("licenceInstalled"));
            check("ux0/license/<titleId>/<contentId>.rif gravado", rifDst.isFile(),
                    "faltou " + rifDst.getPath());
            if (rifDst.isFile()) {
                byte[] a = Files.readAllBytes(rifDst.toPath());
                check("rif gravado byte-a-byte", java.util.Arrays.equals(a, rifCidBytes),
                        "len=" + a.length);
            }
            check("work.bin continua em sce_sys/package",
                    new File(baseLic, "ux0/app/" + TID + "/sce_sys/package/work.bin").isFile(), "faltou work.bin");
        } catch (Exception e) {
            e.printStackTrace();
            check("pkg licenciado conclui", false, e.toString());
        }

        // rif sem content_id: tem de cair no CONTENT_ID do param.sfo
        System.out.println("[5b] rif sem content_id -> usa o do param.sfo");
        File baseLic2 = new File(work, "baseLic2");
        try {
            Map<String, Object> r = PkgExtractor.install(fLic.getPath(), "", rifOk.getPath(), baseLic2.getPath());
            String sfoCid = String.valueOf(r.get("contentId"));
            check("fallback usa contentId do sfo", r.get("licenceInstalled") != null
                            && String.valueOf(r.get("licenceInstalled")).endsWith(sfoCid + ".rif"),
                    "licenceInstalled=" + r.get("licenceInstalled") + " sfoCid=" + sfoCid);
        } catch (Exception e) {
            check("fallback usa contentId do sfo", false, e.toString());
        }

        // ---------------- 6. payload grande: CTR tem de atravessar os chunks
        System.out.println("[6] payload > 256 KB e tamanho NAO multiplo de 16");
        // O instalador le em chunks de 256 KB e mantem um unico keystream CTR
        // para o item inteiro. Um payload maior que o chunk, e com tamanho
        // que nao fecha em bloco de 16, quebra qualquer implementacao que
        // recalcule o keystream por chunk.
        byte[] big = new byte[700003];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 31 + (i >> 11));
        File f5 = new File(work, "big.pkg");
        buildPkg(f5, 0x15, 2, TID, "Jogo Grande", list(
                new Entry("sce_sys/param.sfo", buildSfo(TID, "Jogo Grande")),
                new Entry("sce_pfs/files.db", filesDb),
                new Entry("sce_pfs/unicv.db", unicv),
                new Entry("data.psarc", sealed),
                new Entry("rom/extra.bin", big)), null);
        File base5 = new File(work, "base5");
        try {
            Map<String, Object> r = PkgExtractor.install(f5.getPath(), "", rifOk.getPath(), base5.getPath());
            check("payload grande instala", Boolean.TRUE.equals(r.get("ok")), String.valueOf(r));
            byte[] got5 = Files.readAllBytes(new File(base5, "ux0/app/" + TID + "/rom/extra.bin").toPath());
            check("payload grande byte-a-byte (CTR continuo entre chunks)",
                    java.util.Arrays.equals(got5, big),
                    "len=" + got5.length + " esperado=" + big.length
                            + (got5.length == big.length ? " difere em " + firstDiff(got5, big) : ""));
        } catch (Exception e) {
            check("payload grande instala", false, e.toString());
        }

        // ---------------- 6. PFS com setor de cauda (len % 16 != 0) ------
        System.out.println("[6] PFS com ultimo setor NAO multiplo de 16");
        // 5000 bytes com setor de 4096: o segundo setor tem 904 bytes, e 904
        // % 16 = 8. E' o caminho da cauda (IV = bloco de ciphertext anterior),
        // que nenhum outro teste do harness exercita.
        byte[] odd = new byte[5000];
        for (int i = 0; i < odd.length; i++) odd[i] = (byte) (i * 17 + 3);
        byte[] oddSealed = pfsEncrypt(odd, dbseed, dk, sectorSize);
        byte[] oddFirstSig = hmac(sk, java.util.Arrays.copyOf(oddSealed, (int) sectorSize));
        byte[] unicvOdd = java.util.Arrays.copyOf(unicv, unicv.length);
        System.arraycopy(oddFirstSig, 0, unicvOdd, (int) (pageSize * 2) + 16, 20);
        File f6 = new File(work, "odd.pkg");
        buildPkg(f6, 0x15, 2, TID, "Jogo Cauda", list(
                new Entry("sce_sys/param.sfo", buildSfo(TID, "Jogo Cauda")),
                new Entry("sce_pfs/files.db", filesDb),
                new Entry("sce_pfs/unicv.db", unicvOdd),
                new Entry("data.psarc", oddSealed)), null);
        File base6 = new File(work, "base6");
        try {
            Map<String, Object> r = PkgExtractor.install(f6.getPath(), "", rifOk.getPath(), base6.getPath());
            check("pkg com cauda instala", Boolean.TRUE.equals(r.get("ok")), String.valueOf(r));
            byte[] got6 = Files.readAllBytes(new File(base6, "ux0/app/" + TID + "/data.psarc").toPath());
            check("cauda de setor PFS byte-a-byte",
                    java.util.Arrays.equals(got6, odd),
                    "len=" + got6.length + " esperado=" + odd.length
                            + (got6.length == odd.length ? " difere em " + firstDiff(got6, odd) : ""));
        } catch (Exception e) {
            check("pkg com cauda instala", false, e.toString());
        }

        // ---------------- 7. zRIF em texto (deflate sem dicionario) -----
        System.out.println("[7] zRIF em texto, deflate sem dicionario");
        // O zRIF de verdade e' base64 de um deflate raw com cabecalho de 2
        // bytes. Sem o flag de dicionario o dict fica null, e o codigo antigo
        // perguntava dict.length -> NullPointerException em vez de decodificar.
        byte[] goodRif = Files.readAllBytes(rifOk.toPath());
        File base7 = new File(work, "base7");
        try {
            Map<String, Object> r = PkgExtractor.install(f2.getPath(), zrifText(goodRif), "", base7.getPath());
            check("zRIF sem dicionario instala", Boolean.TRUE.equals(r.get("ok")), String.valueOf(r));
            check("zRIF sem dicionario extrai o jogo",
                    new File(base7, "ux0/app/" + TID + "/data.psarc").isFile(), "faltou psarc");
        } catch (Exception e) {
            check("zRIF sem dicionario instala", false, e.toString());
        }

        // ---------------- 8. zRIF corrompido tem de ser recusado --------
        System.out.println("[8] zRIF corrompido -> recusar");
        File base8 = new File(work, "base8");
        try {
            Map<String, Object> r = PkgExtractor.install(f2.getPath(), "isso-nao-e-um-zrif", "", base8.getPath());
            check("zRIF corrompido deve falhar", false, "instalou mesmo assim: " + r.get("titleId"));
        } catch (Exception e) {
            check("zRIF corrompido -> erro", e.getMessage() != null
                    && e.getMessage().toLowerCase().contains("zrif"), e.getMessage());
        }
        check("nada gravado com zRIF corrompido",
                !new File(base8, "ux0/app/" + TID + "/data.psarc").exists(), "deixou arquivo para tras");

        // ---------------- 9. pflist marca "nenc": nao descriptografar -----
        System.out.println("[9] pflist com flag nenc (param.sfo/clearsign em claro)");
        // Bug achado no Taiko real (PCSG00551): o pflist lista param.sfo e
        // clearsign, mas o PKG os guarda EM CLARO (o jogo precisa do titulo
        // antes de existir chave). A assinatura do primeiro setor deles ainda
        // confere, porque o indice registra o arquivo em claro, entao a busca
        // por tabela via HMAC nao os distingue de um arquivo mesmo cifrado: o
        // instalador os descriptografava e os destruia (param.sfo virava lixo,
        // clearsign perdia o cabecalho ".DRM"). A unica fonte que separa os
        // dois casos e a flag "nenc" da 3a coluna do pflist. No Taiko isso
        // Levava 90/90 arquivos_installados a divergir do Vita3K em 2.
        // Aqui: um arquivo realmente cifrado (data.psarc) e um em claro
        // (param.sfo) no MESMO pkg, para a flag nao desligar a PFS inteira.
        byte[] arcPlain = new byte[4096];
        for (int i = 0; i < arcPlain.length; i++) arcPlain[i] = (byte) (i * 13 + 5);
        byte[] seedA = new byte[20], seedB = new byte[20];
        for (int i = 0; i < 20; i++) { seedA[i] = (byte) (0x40 + i); seedB[i] = (byte) (0x70 + i); }
        byte[] arcSealed = pfsEncrypt(arcPlain, seedA, dk, sectorSize);
        byte[] sfoPlain = buildSfo(TID, "Jogo Nenc");
        // Tabela 1: assinatura sobre o setor cifrado de data.psarc.
        // Tabela 2: assinatura sobre o param.sfo EM CLARO (o caso do bug).
        byte[][] sigs = { hmac(pfsSignatureKey(dk, 7, 1), java.util.Arrays.copyOf(arcSealed, (int) sectorSize)),
                          hmac(pfsSignatureKey(dk, 7, 3), sfoPlain) };
        byte[] unicv2 = buildUnicv2(pageSize, new long[][] { { 1, sectorSize }, { 1, sectorSize } },
                new byte[][] { seedA, seedB }, sigs);
        String pflist = "# List of PFS files/dirs (Don't edit this file).\n"
                + "#\trevision\t1\n#\tpfs_mode\t10\n#\tpfs_flag\t3\n#\tpfs_fver\t5\n"
                + "sce_sys\tsys\tdir\t0x00000001\t0\t0\n"
                + "sce_sys/param.sfo\tsys\tnenc\t0x00000009\t" + sfoPlain.length + "\t" + hex(seedB) + "\n"
                + "data.psarc\tro\t\t0x0000000b\t" + arcPlain.length + "\t" + hex(seedA) + "\n";
        File f9 = new File(work, "nenc.pkg");
        buildPkg(f9, 0x15, 2, TID, "Jogo Nenc", list(
                new Entry("sce_sys/param.sfo", sfoPlain),
                new Entry("sce_pfs/files.db", filesDb),
                new Entry("sce_pfs/unicv.db", unicv2),
                new Entry("sce_pfs/pflist", pflist.getBytes("UTF-8")),
                new Entry("data.psarc", arcSealed)), null);
        File base9 = new File(work, "base9");
        try {
            Map<String, Object> r = PkgExtractor.install(f9.getPath(), "", rifOk.getPath(), base9.getPath());
            check("pkg com nenc instala", Boolean.TRUE.equals(r.get("ok")), String.valueOf(r));
            check("so o arquivo cifrado foi descriptografado (1 de 2)",
                    Integer.valueOf(1).equals(r.get("pfsFiles")), "pfsFiles=" + r.get("pfsFiles"));
            byte[] gotArc = Files.readAllBytes(new File(base9, "ux0/app/" + TID + "/data.psarc").toPath());
            check("arquivo cifrado continua descriptografando (nenc nao desliga a PFS)",
                    java.util.Arrays.equals(gotArc, arcPlain),
                    "len=" + gotArc.length + " difere em " + firstDiff(gotArc, arcPlain));
            byte[] gotSfo = Files.readAllBytes(new File(base9, "ux0/app/" + TID + "/sce_sys/param.sfo").toPath());
            check("param.sfo marcado nenc fica intacto (o bug do Taiko)",
                    java.util.Arrays.equals(gotSfo, sfoPlain),
                    "len=" + gotSfo.length + " esperado=" + sfoPlain.length
                            + (gotSfo.length == sfoPlain.length ? " difere em " + firstDiff(gotSfo, sfoPlain)
                                    : " foi descriptografado"));
        } catch (Exception e) {
            check("pkg com nenc instala", false, e.toString());
        }

        System.out.println();
        System.out.println(pass + " passaram, " + fail + " falharam");
        if (fail > 0) System.exit(1);
    }

    /** unicv.db com N tabelas de uma pagina cada (tabelas em pag 1,3,5...; assinaturas na pagina seguinte). */
    static byte[] buildUnicv2(long pageSize, long[][] shapes, byte[][] seeds, byte[][] sigs) throws Exception {
        int n = shapes.length;
        byte[] u = new byte[(int) (pageSize * (2 * n + 2))];
        System.arraycopy("SCEIRODB".getBytes("ISO-8859-1"), 0, u, 0, 8);
        le32(u, 12, (int) pageSize);
        le32(u, 24, (int) (pageSize * 2 * n));           // dataSize -> dataEnd = pageSize*(2n+1)
        for (int i = 0; i < n; i++) {
            int tbl = (int) (pageSize * (2 * i + 1));     // tabelas em pag 1,3,5...
            System.arraycopy("SCEIFTBL".getBytes("ISO-8859-1"), 0, u, tbl, 8);
            le32(u, tbl + 8, 2);                          // version
            le32(u, tbl + 12, (int) pageSize);
            le32(u, tbl + 16, 1);                         // maxSignatures
            le32(u, tbl + 20, (int) shapes[i][0]);       // sectors
            le32(u, tbl + 24, (int) shapes[i][1]);       // sectorSize
            System.arraycopy(seeds[i], 0, u, tbl + 52, 20);
            System.arraycopy(sigs[i], 0, u, (int) (pageSize * (2 * i + 2)) + 16, 20);
        }
        return u;
    }

    /** zRIF de verdade: base64(0x08 0x1D + deflate-raw(rif)), sem dicionario. */
    static String zrifText(byte[] rif) throws Exception {
        java.util.zip.Deflater d = new java.util.zip.Deflater(9, true);
        d.setInput(rif);
        d.finish();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        while (!d.finished()) {
            int n = d.deflate(tmp);
            if (n == 0) break;
            body.write(tmp, 0, n);
        }
        d.end();
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write(0x08);
        all.write(0x1D);   // (0x08 << 8 + 0x1D) % 31 == 0 e nibble baixo == 8
        all.write(body.toByteArray());
        return java.util.Base64.getEncoder().encodeToString(all.toByteArray());
    }

    static long firstDiff(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) if (a[i] != b[i]) return i;
        return n;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static List<Entry> list(Entry... e) {
        List<Entry> l = new ArrayList<Entry>();
        for (Entry x : e) l.add(x);
        return l;
    }

    /** PNG minúsculo e válido, só para o instalador ter o que extrair. */
    static byte[] png(int size) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x2b, 0x9d, 0xf0));
        g.fillRect(0, 0, size, size);
        g.dispose();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }

    static void deleteRec(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) for (File k : f.listFiles()) deleteRec(k);
        f.delete();
    }
}
