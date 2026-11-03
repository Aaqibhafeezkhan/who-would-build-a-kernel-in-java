/*
 * ====================================================
 * Copyright (C) 2004 by Sun Microsystems, Inc. All rights reserved.
 *
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 * ====================================================
 */
package java.lang;

import static java.lang.StrictMath.fromHi;
import static java.lang.StrictMath.hi;
import static java.lang.StrictMath.highWord;
import static java.lang.StrictMath.lo;
import static java.lang.StrictMath.scalbn;

/**
 * fdlibm's sin, cos, tan, asin, acos, atan and atan2 for StrictMath, translated from Sun's C
 * (s_sin.c, s_cos.c, s_tan.c, k_sin.c, k_cos.c, k_tan.c, e_rem_pio2.c, k_rem_pio2.c, e_asin.c,
 * e_acos.c, s_atan.c, e_atan2.c) the same way as exp, log and pow.
 */
final class Trigonometry {

    private static final double HUGE = 1.0e300;
    private static final double TINY = 1.0e-300;
    private static final double TWO24 = 1.67772160000000000000e+07;
    private static final double TWO_M24 = 5.96046447753906250000e-08;

    private static final double PI = 3.14159265358979311600e+00;
    private static final double PI_LO = 1.2246467991473531772e-16;
    private static final double PIO2_HI = 1.57079632679489655800e+00;
    private static final double PIO2_LO = 6.12323399573676603587e-17;
    private static final double PIO4_HI = 7.85398163397448278999e-01;
    private static final double PIO4_LO = 3.06161699786838301793e-17;

    private static final double S1 = -1.66666666666666324348e-01;
    private static final double S2 = 8.33333333332248946124e-03;
    private static final double S3 = -1.98412698298579493134e-04;
    private static final double S4 = 2.75573137070700676789e-06;
    private static final double S5 = -2.50507602534068634195e-08;
    private static final double S6 = 1.58969099521155010221e-10;

    private static final double C1 = 4.16666666666666019037e-02;
    private static final double C2 = -1.38888888888741095749e-03;
    private static final double C3 = 2.48015872894767294178e-05;
    private static final double C4 = -2.75573143513906633035e-07;
    private static final double C5 = 2.08757232129817482790e-09;
    private static final double C6 = -1.13596475577881948265e-11;

    private static final double[] T = {
        3.33333333333334091986e-01, 1.33333333333201242699e-01, 5.39682539762260521377e-02,
        2.18694882948595424599e-02, 8.86323982359930005737e-03, 3.59207910759131235356e-03,
        1.45620945432529025516e-03, 5.88041240820264096874e-04, 2.46463134818469906812e-04,
        7.81794442939557092300e-05, 7.14072491382608190305e-05, -1.85586374855275456654e-05,
        2.59073051863633712884e-05,
    };

    private static final double[] ATAN_HI = {
        4.63647609000806093515e-01, 7.85398163397448278999e-01, 9.82793723247329054082e-01,
        1.57079632679489655800e+00,
    };
    private static final double[] ATAN_LO = {
        2.26987774529616870924e-17, 3.06161699786838301793e-17, 1.39033110312309984516e-17,
        6.12323399573676603587e-17,
    };
    private static final double[] AT = {
        3.33333333333329318027e-01, -1.99999999998764832476e-01, 1.42857142725034663711e-01,
        -1.11111104054623557880e-01, 9.09088713343650656196e-02, -7.69187620504482999495e-02,
        6.66107313738753120669e-02, -5.83357013379057348645e-02, 4.97687799461593236017e-02,
        -3.65315727442169155270e-02, 1.62858201153657823623e-02,
    };

    private static final double PS0 = 1.66666666666666657415e-01;
    private static final double PS1 = -3.25565818622400915405e-01;
    private static final double PS2 = 2.01212532134862925881e-01;
    private static final double PS3 = -4.00555345006794114027e-02;
    private static final double PS4 = 7.91534994289814532176e-04;
    private static final double PS5 = 3.47933107596021167570e-05;
    private static final double QS1 = -2.40339491173441421878e+00;
    private static final double QS2 = 2.02094576023350569471e+00;
    private static final double QS3 = -6.88283971605453293030e-01;
    private static final double QS4 = 7.70381505559019352791e-02;

    // pi/2 in pieces of 33 bits, each with the rest of pi/2 after it, for reducing by up to 2^19 * pi/2.
    private static final double INV_PIO2 = 6.36619772367581382433e-01;
    private static final double PIO2_1 = 1.57079632673412561417e+00;
    private static final double PIO2_1T = 6.07710050650619224932e-11;
    private static final double PIO2_2 = 6.07710050630396597660e-11;
    private static final double PIO2_2T = 2.02226624879595063154e-21;
    private static final double PIO2_3 = 2.02226624871116645580e-21;
    private static final double PIO2_3T = 8.47842766036889956997e-32;

    // High words of n * pi/2 for n up to 32, where the reduction cancels the most bits.
    private static final int[] NPIO2_HW = {
        0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB, 0x402921FB,
        0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB,
        0x403AB41B, 0x403C463A, 0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
        0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB,
    };

    // 2/pi in 24-bit pieces, enough for the largest double.
    private static final int[] TWO_OVER_PI = {
        0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62, 0x95993C, 0x439041, 0xFE5163,
        0xABDEBB, 0xC561B7, 0x246E3A, 0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129,
        0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C, 0x7026B4, 0x5F7E41, 0x3991D6, 0x398353, 0x39F49C,
        0x845F8B, 0xBDF928, 0x3B1FF8, 0x97FFDE, 0x05980F, 0xEF2F11, 0x8B5A0A, 0x6D1F6D, 0x367ECF,
        0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5, 0xF17B3D, 0x0739F7, 0x8A5292,
        0xEA6BFB, 0x5FB11F, 0x8D5D08, 0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3,
        0x91615E, 0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880, 0x4D7327, 0x310606, 0x1556CA,
        0x73A8C9, 0x60E27B, 0xC08C6B,
    };

    // pi/2 in 24-bit pieces.
    private static final double[] PI_O2 = {
        1.57079625129699707031e+00, 7.54978941586159635335e-08, 5.39030252995776476554e-15,
        3.28200341580791294123e-22, 1.27065575308067607349e-29, 1.22933308981111328932e-36,
        2.73370053816464559624e-44, 2.16741683877804819444e-51,
    };

    private Trigonometry() {
    }

    static double sin(double x) {
        int ix = hi(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            return kernelSin(x, 0.0, 0);
        }
        if (ix >= 0x7ff00000) {
            return x - x;
        }
        double[] y = new double[2];
        switch (remPio2(x, y) & 3) {
            case 0:
                return kernelSin(y[0], y[1], 1);
            case 1:
                return kernelCos(y[0], y[1]);
            case 2:
                return -kernelSin(y[0], y[1], 1);
            default:
                return -kernelCos(y[0], y[1]);
        }
    }

    static double cos(double x) {
        int ix = hi(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            return kernelCos(x, 0.0);
        }
        if (ix >= 0x7ff00000) {
            return x - x;
        }
        double[] y = new double[2];
        switch (remPio2(x, y) & 3) {
            case 0:
                return kernelCos(y[0], y[1]);
            case 1:
                return -kernelSin(y[0], y[1], 1);
            case 2:
                return -kernelCos(y[0], y[1]);
            default:
                return kernelSin(y[0], y[1], 1);
        }
    }

    static double tan(double x) {
        int ix = hi(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            return kernelTan(x, 0.0, 1);
        }
        if (ix >= 0x7ff00000) {
            return x - x;
        }
        double[] y = new double[2];
        int n = remPio2(x, y);
        return kernelTan(y[0], y[1], 1 - ((n & 1) << 1));
    }

    static double asin(double x) {
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        if (ix >= 0x3ff00000) {
            if (((ix - 0x3ff00000) | lo(x)) == 0) {
                return x * PIO2_HI + x * PIO2_LO;
            }
            return (x - x) / (x - x);
        }
        if (ix < 0x3fe00000) {
            if (ix < 0x3e400000 && HUGE + x > 1.0) {
                return x;
            }
            double t = x * x;
            double p = t * (PS0 + t * (PS1 + t * (PS2 + t * (PS3 + t * (PS4 + t * PS5)))));
            double q = 1.0 + t * (QS1 + t * (QS2 + t * (QS3 + t * QS4)));
            double w = p / q;
            return x + x * w;
        }
        double w = 1.0 - Math.abs(x);
        double t = w * 0.5;
        double p = t * (PS0 + t * (PS1 + t * (PS2 + t * (PS3 + t * (PS4 + t * PS5)))));
        double q = 1.0 + t * (QS1 + t * (QS2 + t * (QS3 + t * QS4)));
        double s = Math.sqrt(t);
        if (ix >= 0x3FEF3333) {
            w = p / q;
            t = PIO2_HI - (2.0 * (s + s * w) - PIO2_LO);
        } else {
            w = highWord(s);
            double c = (t - w * w) / (s + w);
            double r = p / q;
            p = 2.0 * s * r - (PIO2_LO - 2.0 * c);
            q = PIO4_HI - 2.0 * w;
            t = PIO4_HI - (p - q);
        }
        return hx > 0 ? t : -t;
    }

    static double acos(double x) {
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        if (ix >= 0x3ff00000) {
            if (((ix - 0x3ff00000) | lo(x)) == 0) {
                return hx > 0 ? 0.0 : PI + 2.0 * PIO2_LO;
            }
            return (x - x) / (x - x);
        }
        if (ix < 0x3fe00000) {
            if (ix <= 0x3c600000) {
                return PIO2_HI + PIO2_LO;
            }
            double z = x * x;
            double p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
            double q = 1.0 + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
            double r = p / q;
            return PIO2_HI - (x - (PIO2_LO - x * r));
        }
        if (hx < 0) {
            double z = (1.0 + x) * 0.5;
            double p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
            double q = 1.0 + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
            double s = Math.sqrt(z);
            double r = p / q;
            double w = r * s - PIO2_LO;
            return PI - 2.0 * (s + w);
        }
        double z = (1.0 - x) * 0.5;
        double s = Math.sqrt(z);
        double df = highWord(s);
        double c = (z - df * df) / (s + df);
        double p = z * (PS0 + z * (PS1 + z * (PS2 + z * (PS3 + z * (PS4 + z * PS5)))));
        double q = 1.0 + z * (QS1 + z * (QS2 + z * (QS3 + z * QS4)));
        double r = p / q;
        double w = r * s + c;
        return 2.0 * (df + w);
    }

    static double atan(double x) {
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        int id;
        if (ix >= 0x44100000) {
            if (ix > 0x7ff00000 || (ix == 0x7ff00000 && lo(x) != 0)) {
                return x + x;
            }
            return hx > 0 ? ATAN_HI[3] + ATAN_LO[3] : -ATAN_HI[3] - ATAN_LO[3];
        }
        if (ix < 0x3fdc0000) {
            if (ix < 0x3e200000 && HUGE + x > 1.0) {
                return x;
            }
            id = -1;
        } else {
            x = Math.abs(x);
            if (ix < 0x3ff30000) {
                if (ix < 0x3fe60000) {
                    id = 0;
                    x = (2.0 * x - 1.0) / (2.0 + x);
                } else {
                    id = 1;
                    x = (x - 1.0) / (x + 1.0);
                }
            } else if (ix < 0x40038000) {
                id = 2;
                x = (x - 1.5) / (1.0 + 1.5 * x);
            } else {
                id = 3;
                x = -1.0 / x;
            }
        }
        double z = x * x;
        double w = z * z;
        double s1 = z * (AT[0] + w * (AT[2] + w * (AT[4] + w * (AT[6] + w * (AT[8] + w * AT[10])))));
        double s2 = w * (AT[1] + w * (AT[3] + w * (AT[5] + w * (AT[7] + w * AT[9]))));
        if (id < 0) {
            return x - x * (s1 + s2);
        }
        z = ATAN_HI[id] - ((x * (s1 + s2) - ATAN_LO[id]) - x);
        return hx < 0 ? -z : z;
    }

    static double atan2(double y, double x) {
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        int lx = lo(x);
        int hy = hi(y);
        int iy = hy & 0x7fffffff;
        int ly = lo(y);
        if ((ix | ((lx | -lx) >>> 31)) > 0x7ff00000 || (iy | ((ly | -ly) >>> 31)) > 0x7ff00000) {
            return x + y;
        }
        if (((hx - 0x3ff00000) | lx) == 0) {
            return atan(y);
        }
        // Twice x's sign bit plus y's.
        int m = ((hy >> 31) & 1) | ((hx >> 30) & 2);
        if ((iy | ly) == 0) {
            switch (m) {
                case 0:
                case 1:
                    return y;
                case 2:
                    return PI + TINY;
                default:
                    return -PI - TINY;
            }
        }
        if ((ix | lx) == 0) {
            return hy < 0 ? -PIO2_HI - TINY : PIO2_HI + TINY;
        }
        if (ix == 0x7ff00000) {
            if (iy == 0x7ff00000) {
                switch (m) {
                    case 0:
                        return PIO4_HI + TINY;
                    case 1:
                        return -PIO4_HI - TINY;
                    case 2:
                        return 3.0 * PIO4_HI + TINY;
                    default:
                        return -3.0 * PIO4_HI - TINY;
                }
            }
            switch (m) {
                case 0:
                    return 0.0;
                case 1:
                    return -0.0;
                case 2:
                    return PI + TINY;
                default:
                    return -PI - TINY;
            }
        }
        if (iy == 0x7ff00000) {
            return hy < 0 ? -PIO2_HI - TINY : PIO2_HI + TINY;
        }
        int k = (iy - ix) >> 20;
        double z;
        if (k > 60) {
            z = PIO2_HI + 0.5 * PI_LO;
        } else if (hx < 0 && k < -60) {
            z = 0.0;
        } else {
            z = atan(Math.abs(y / x));
        }
        switch (m) {
            case 0:
                return z;
            case 1:
                return -z;
            case 2:
                return PI - (z - PI_LO);
            default:
                return (z - PI_LO) - PI;
        }
    }

    /** sin(x + y) for |x + y| up to about pi/4, where y is x's tail; iy is 0 when y is zero. */
    private static double kernelSin(double x, double y, int iy) {
        int ix = hi(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int) x == 0) {
            return x;
        }
        double z = x * x;
        double v = z * x;
        double r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
        if (iy == 0) {
            return x + v * (S1 + z * r);
        }
        return x - ((z * (0.5 * y - v * r) - y) - v * S1);
    }

    private static double kernelCos(double x, double y) {
        int ix = hi(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int) x == 0) {
            return 1.0;
        }
        double z = x * x;
        double r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
        if (ix < 0x3FD33333) {
            return 1.0 - (0.5 * z - (z * r - x * y));
        }
        double qx = ix > 0x3fe90000 ? 0.28125 : fromHi(ix - 0x00200000);
        double hz = 0.5 * z - qx;
        double a = 1.0 - qx;
        return a - (hz - (z * r - x * y));
    }

    /** tan(x + y) when iy is 1, -1/tan(x + y) when it's -1. */
    private static double kernelTan(double x, double y, int iy) {
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        if (ix < 0x3e300000 && (int) x == 0) {
            if (((ix | lo(x)) | (iy + 1)) == 0) {
                return 1.0 / Math.abs(x);
            }
            if (iy == 1) {
                return x;
            }
            double w = x + y;
            double z = highWord(w);
            double v = y - (z - x);
            double a = -1.0 / w;
            double t = highWord(a);
            double s = 1.0 + t * z;
            return t + a * (s + t * v);
        }
        if (ix >= 0x3FE59428) {
            if (hx < 0) {
                x = -x;
                y = -y;
            }
            double z = PIO4_HI - x;
            double w = PIO4_LO - y;
            x = z + w;
            y = 0.0;
        }
        double z = x * x;
        double w = z * z;
        double r = T[1] + w * (T[3] + w * (T[5] + w * (T[7] + w * (T[9] + w * T[11]))));
        double v = z * (T[2] + w * (T[4] + w * (T[6] + w * (T[8] + w * (T[10] + w * T[12])))));
        double s = z * x;
        r = y + z * (s * (r + v) + y);
        r += T[0] * s;
        w = x + r;
        if (ix >= 0x3FE59428) {
            v = iy;
            return (double) (1 - ((hx >> 30) & 2)) * (v - 2.0 * (x - (w * w / (w + v) - r)));
        }
        if (iy == 1) {
            return w;
        }
        // -1/(x + r), carefully enough to stay within an ulp.
        z = highWord(w);
        v = r - (z - x);
        double a = -1.0 / w;
        double t = highWord(a);
        s = 1.0 + t * z;
        return t + a * (s + t * v);
    }

    /** x - n * pi/2 as y[0] + y[1], returning n. */
    private static int remPio2(double x, double[] y) {
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            y[0] = x;
            y[1] = 0;
            return 0;
        }
        if (ix < 0x4002d97c) {
            if (hx > 0) {
                double z = x - PIO2_1;
                if (ix != 0x3ff921fb) {
                    y[0] = z - PIO2_1T;
                    y[1] = (z - y[0]) - PIO2_1T;
                } else {
                    z -= PIO2_2;
                    y[0] = z - PIO2_2T;
                    y[1] = (z - y[0]) - PIO2_2T;
                }
                return 1;
            }
            double z = x + PIO2_1;
            if (ix != 0x3ff921fb) {
                y[0] = z + PIO2_1T;
                y[1] = (z - y[0]) + PIO2_1T;
            } else {
                z += PIO2_2;
                y[0] = z + PIO2_2T;
                y[1] = (z - y[0]) + PIO2_2T;
            }
            return -1;
        }
        if (ix <= 0x413921fb) {
            double t = Math.abs(x);
            int n = (int) (t * INV_PIO2 + 0.5);
            double fn = n;
            double r = t - fn * PIO2_1;
            double w = fn * PIO2_1T;
            y[0] = r - w;
            if (n >= 32 || ix == NPIO2_HW[n - 1]) {
                int j = ix >> 20;
                int i = j - ((hi(y[0]) >> 20) & 0x7ff);
                if (i > 16) {
                    t = r;
                    w = fn * PIO2_2;
                    r = t - w;
                    w = fn * PIO2_2T - ((t - r) - w);
                    y[0] = r - w;
                    i = j - ((hi(y[0]) >> 20) & 0x7ff);
                    if (i > 49) {
                        t = r;
                        w = fn * PIO2_3;
                        r = t - w;
                        w = fn * PIO2_3T - ((t - r) - w);
                        y[0] = r - w;
                    }
                }
            }
            y[1] = (r - y[0]) - w;
            if (hx < 0) {
                y[0] = -y[0];
                y[1] = -y[1];
                return -n;
            }
            return n;
        }
        if (ix >= 0x7ff00000) {
            y[0] = x - x;
            y[1] = y[0];
            return 0;
        }
        // |x| scaled to between 2^23 and 2^24, then cut into three 24-bit pieces.
        int e0 = (ix >> 20) - 1046;
        double z = Double.longBitsToDouble((long) (ix - (e0 << 20)) << 32 | lo(x) & 0xffffffffL);
        double[] tx = new double[3];
        for (int i = 0; i < 2; i++) {
            tx[i] = (int) z;
            z = (z - tx[i]) * TWO24;
        }
        tx[2] = z;
        int nx = 3;
        while (tx[nx - 1] == 0.0) {
            nx--;
        }
        int n = kernelRemPio2(tx, y, e0, nx);
        if (hx < 0) {
            y[0] = -y[0];
            y[1] = -y[1];
            return -n;
        }
        return n;
    }

    /**
     * The reduction for large x, given as nx 24-bit pieces x[i] * 2^(e0 - 24i): multiplies by just
     * the bits of 2/pi that land near the binary point, and fetches more when the result cancels.
     * This is k_rem_pio2.c at its prec 2 (y[0] + y[1], 53 bits each).
     */
    private static int kernelRemPio2(double[] x, double[] y, int e0, int nx) {
        int jk = 4;
        int jp = jk;
        int[] iq = new int[20];
        double[] f = new double[20];
        double[] fq = new double[20];
        double[] q = new double[20];

        int jx = nx - 1;
        int jv = Math.max((e0 - 3) / 24, 0);
        int q0 = e0 - 24 * (jv + 1);

        int j = jv - jx;
        int m = jx + jk;
        for (int i = 0; i <= m; i++, j++) {
            f[i] = j < 0 ? 0.0 : (double) TWO_OVER_PI[j];
        }
        for (int i = 0; i <= jk; i++) {
            double fw = 0.0;
            for (j = 0; j <= jx; j++) {
                fw += x[j] * f[jx + i - j];
            }
            q[i] = fw;
        }

        int jz = jk;
        double z;
        int n;
        int ih;
        while (true) {
            z = q[jz];
            for (int i = 0, k = jz; k > 0; i++, k--) {
                double fw = (int) (TWO_M24 * z);
                iq[i] = (int) (z - TWO24 * fw);
                z = q[k - 1] + fw;
            }

            z = scalbn(z, q0);
            z -= 8.0 * Math.floor(z * 0.125);
            n = (int) z;
            z -= n;
            ih = 0;
            if (q0 > 0) {
                int i = iq[jz - 1] >> (24 - q0);
                n += i;
                iq[jz - 1] -= i << (24 - q0);
                ih = iq[jz - 1] >> (23 - q0);
            } else if (q0 == 0) {
                ih = iq[jz - 1] >> 23;
            } else if (z >= 0.5) {
                ih = 2;
            }

            // The fraction is over a half: take 1 - fraction, and n + 1.
            if (ih > 0) {
                n += 1;
                int carry = 0;
                for (int i = 0; i < jz; i++) {
                    j = iq[i];
                    if (carry == 0) {
                        if (j != 0) {
                            carry = 1;
                            iq[i] = 0x1000000 - j;
                        }
                    } else {
                        iq[i] = 0xffffff - j;
                    }
                }
                if (q0 == 1) {
                    iq[jz - 1] &= 0x7fffff;
                } else if (q0 == 2) {
                    iq[jz - 1] &= 0x3fffff;
                }
                if (ih == 2) {
                    z = 1.0 - z;
                    if (carry != 0) {
                        z -= scalbn(1.0, q0);
                    }
                }
            }

            if (z != 0.0) {
                break;
            }
            j = 0;
            for (int i = jz - 1; i >= jk; i--) {
                j |= iq[i];
            }
            if (j != 0) {
                break;
            }
            int k = 1;
            while (iq[jk - k] == 0) {
                k++;
            }
            for (int i = jz + 1; i <= jz + k; i++) {
                f[jx + i] = TWO_OVER_PI[jv + i];
                double fw = 0.0;
                for (j = 0; j <= jx; j++) {
                    fw += x[j] * f[jx + i - j];
                }
                q[i] = fw;
            }
            jz += k;
        }

        if (z == 0.0) {
            jz -= 1;
            q0 -= 24;
            while (iq[jz] == 0) {
                jz--;
                q0 -= 24;
            }
        } else {
            z = scalbn(z, -q0);
            if (z >= TWO24) {
                double fw = (int) (TWO_M24 * z);
                iq[jz] = (int) (z - TWO24 * fw);
                jz += 1;
                q0 += 24;
                iq[jz] = (int) fw;
            } else {
                iq[jz] = (int) z;
            }
        }

        double fw = scalbn(1.0, q0);
        for (int i = jz; i >= 0; i--) {
            q[i] = fw * iq[i];
            fw *= TWO_M24;
        }
        for (int i = jz; i >= 0; i--) {
            fw = 0.0;
            for (int k = 0; k <= jp && k <= jz - i; k++) {
                fw += PI_O2[k] * q[i + k];
            }
            fq[jz - i] = fw;
        }

        fw = 0.0;
        for (int i = jz; i >= 0; i--) {
            fw += fq[i];
        }
        y[0] = ih == 0 ? fw : -fw;
        fw = fq[0] - fw;
        for (int i = 1; i <= jz; i++) {
            fw += fq[i];
        }
        y[1] = ih == 0 ? fw : -fw;
        return n & 7;
    }
}
