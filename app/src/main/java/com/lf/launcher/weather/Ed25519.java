package com.lf.launcher.weather;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Ed25519 签名（RFC 8032），纯 Java、零依赖。
 *
 * ## 为什么自己写
 *
 * 和风 JWT 用的是 {@code EdDSA}（Ed25519）。Android 要到 **API 33** 才在 Conscrypt 里提供
 * Ed25519，这台车机是 API 28，{@code KeyFactory.getInstance("Ed25519")} 直接抛
 * {@code NoSuchAlgorithmException}；而工程一直 {@code --offline} 编译，加不了 BouncyCastle。
 * 所以自带一份。
 *
 * ## 为什么用 BigInteger 而不是 ref10 那套
 *
 * ref10 / TweetNaCl 用 16 个 16-bit limb 做有限域运算，快，但那是几百行魔数，写错一位
 * 就是「签名能生成、验证永远失败」，还很难查。这里选**定义式实现**：直接照 RFC 8032 的
 * 曲线方程算，慢一个量级，但每一步都能对着规范核。天气 JWT 十几分钟才签一次，
 * 一次几十毫秒完全无所谓 —— 正确性远比速度重要。
 *
 * 正确性由 RFC 8032 §7.1 的测试向量保证（见项目根 {@code Ed25519Test}）。
 */
public final class Ed25519 {

    /** 素域模数 p = 2^255 - 19 */
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));

    /** 子群阶 L = 2^252 + 27742317777372353535851937790883648493 */
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    private static final BigInteger ZERO = BigInteger.ZERO;
    private static final BigInteger ONE = BigInteger.ONE;
    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger EIGHT = BigInteger.valueOf(8);

    /** 曲线参数 d = -121665 / 121666 mod p */
    private static final BigInteger D = BigInteger.valueOf(121665).negate()
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);

    /** sqrt(-1) = 2^((p-1)/4) mod p */
    private static final BigInteger SQRT_M1 = TWO.modPow(P.subtract(ONE).shiftRight(2), P);

    /** 基点 B：y = 4/5，x 取偶的那一支 */
    private static final BigInteger BY = BigInteger.valueOf(4)
            .multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
    private static final BigInteger[] BASE = {recoverX(BY), BY};

    private Ed25519() {
    }

    /**
     * 从 PKCS#8 的 32 字节种子算出 32 字节公钥。
     *
     * 调用方一般不需要它 —— 除非要在界面上显示「这把私钥对应的公钥」，方便和控制台核对。
     */
    public static byte[] publicKey(byte[] seed32) {
        if (seed32.length != 32) throw new IllegalArgumentException("种子必须是 32 字节");
        return encode(mul(BASE, scalarFromSeed(seed32)));
    }

    /**
     * 签名。返回 64 字节（R || S）。
     *
     * @param seed32 32 字节私钥种子（PKCS#8 里 {@code 04 20} 后面那 32 字节）
     * @param msg    要签的内容，JWT 里就是 {@code base64url(header) + "." + base64url(payload)}
     */
    public static byte[] sign(byte[] seed32, byte[] msg) {
        if (seed32.length != 32) throw new IllegalArgumentException("种子必须是 32 字节");

        byte[] h = sha512(seed32);
        byte[] az = Arrays.copyOfRange(h, 0, 32);
        // 按 RFC 8032 的 clamp：清低 3 位、清最高位、置次高位
        az[0] &= (byte) 248;
        az[31] &= (byte) 127;
        az[31] |= (byte) 64;
        BigInteger a = fromLE(az);

        byte[] pub = encode(mul(BASE, a));

        byte[] prefix = Arrays.copyOfRange(h, 32, 64);
        BigInteger r = fromLE(sha512(concat(prefix, msg))).mod(L);
        byte[] rEnc = encode(mul(BASE, r));

        BigInteger k = fromLE(sha512(concat(concat(rEnc, pub), msg))).mod(L);
        BigInteger s = r.add(k.multiply(a)).mod(L);

        byte[] out = new byte[64];
        System.arraycopy(rEnc, 0, out, 0, 32);
        System.arraycopy(toLE(s, 32), 0, out, 32, 32);
        return out;
    }

    // ------------------------------------------------------------------ 曲线运算

    private static BigInteger scalarFromSeed(byte[] seed32) {
        byte[] h = sha512(seed32);
        byte[] az = Arrays.copyOfRange(h, 0, 32);
        az[0] &= (byte) 248;
        az[31] &= (byte) 127;
        az[31] |= (byte) 64;
        return fromLE(az);
    }

    /** 由 y 还原 x：x² = (y²-1) / (d·y²+1)，取偶的那一支（RFC 8032 §5.1.3） */
    private static BigInteger recoverX(BigInteger y) {
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger num = y2.subtract(ONE).mod(P);
        BigInteger den = D.multiply(y2).add(ONE).mod(P);
        BigInteger x2 = num.multiply(den.modInverse(P)).mod(P);

        // p ≡ 5 (mod 8)：候选根是 x2^((p+3)/8)，不对再乘 sqrt(-1)
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).divide(EIGHT), P);
        if (!x.multiply(x).mod(P).equals(x2)) {
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (!x.multiply(x).mod(P).equals(x2)) {
            throw new IllegalStateException("这个 y 不在曲线上");
        }
        // 编码时用最低位存 x 的奇偶，取偶数分支便于统一
        return x.testBit(0) ? P.subtract(x) : x;
    }

    /** 点加（a = -1 的 twisted Edwards，仿射坐标） */
    private static BigInteger[] add(BigInteger[] p1, BigInteger[] p2) {
        BigInteger x1 = p1[0];
        BigInteger y1 = p1[1];
        BigInteger x2 = p2[0];
        BigInteger y2 = p2[1];

        BigInteger t = D.multiply(x1).multiply(x2).multiply(y1).multiply(y2).mod(P);

        BigInteger xNum = x1.multiply(y2).add(x2.multiply(y1)).mod(P);
        BigInteger xDen = ONE.add(t).mod(P);
        BigInteger yNum = y1.multiply(y2).add(x1.multiply(x2)).mod(P);
        BigInteger yDen = ONE.subtract(t).mod(P);

        return new BigInteger[]{
                xNum.multiply(xDen.modInverse(P)).mod(P),
                yNum.multiply(yDen.modInverse(P)).mod(P),
        };
    }

    /** 标量乘：double-and-add。够用就行，不追求常数时间（这里是签自己的 token，没有旁路风险） */
    private static BigInteger[] mul(BigInteger[] point, BigInteger k) {
        BigInteger[] r = {ZERO, ONE}; // 单位元 (0, 1)
        BigInteger[] q = point;
        int bits = k.bitLength();
        for (int i = 0; i < bits; i++) {
            if (k.testBit(i)) r = add(r, q);
            q = add(q, q);
        }
        return r;
    }

    /** 编码点：32 字节小端 y，最高位放 x 的奇偶 */
    private static byte[] encode(BigInteger[] point) {
        byte[] out = toLE(point[1], 32);
        if (point[0].testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    // ------------------------------------------------------------------ 字节工具

    private static byte[] sha512(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("系统没有 SHA-512", e);
        }
    }

    /** 小端字节 → 正整数 */
    private static BigInteger fromLE(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) be[i] = le[le.length - 1 - i];
        return new BigInteger(1, be);
    }

    /** 正整数 → 定长小端字节 */
    private static byte[] toLE(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        // toByteArray 可能带符号位前导 0，也可能不足 len，两头都要掐
        for (int i = 0; i < len; i++) {
            int src = be.length - 1 - i;
            out[i] = src >= 0 ? be[src] : 0;
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
