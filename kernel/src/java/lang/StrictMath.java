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

/**
 * fdlibm's exp, log and pow, translated from Sun's C (e_exp.c, e_log.c, e_pow.c, s_scalbn.c), which
 * is what StrictMath is specified to return bit for bit; the trig functions are in Trigonometry. The C works on the high and low 32-bit
 * words of a double, so the translation does too, to keep every rounding step the same.
 */
public final class StrictMath {

    private static final double HUGE = 1.0e+300;
    private static final double TINY = 1.0e-300;
    private static final double TWO54 = 1.80143985094819840000e+16;
    private static final double TWO_M54 = 5.55111512312578270212e-17;
    private static final double TWO53 = 9007199254740992.0;
    private static final double TWO_M1000 = 9.33263618503218878990e-302;

    private static final double LN2_HI = 6.93147180369123816490e-01;
    private static final double LN2_LO = 1.90821492927058770002e-10;
    private static final double INV_LN2 = 1.44269504088896338700e+00;
    private static final double INV_LN2_HI = 1.44269502162933349609e+00;
    private static final double INV_LN2_LO = 1.92596299112661746887e-08;

    private static final double EXP_OVERFLOW = 7.09782712893383973096e+02;
    private static final double EXP_UNDERFLOW = -7.45133219101941108420e+02;
    private static final double P1 = 1.66666666666666019037e-01;
    private static final double P2 = -2.77777777770155933842e-03;
    private static final double P3 = 6.61375632143793436117e-05;
    private static final double P4 = -1.65339022054652515390e-06;
    private static final double P5 = 4.13813679705723846039e-08;

    private static final double LG1 = 6.666666666666735130e-01;
    private static final double LG2 = 3.999999999940941908e-01;
    private static final double LG3 = 2.857142874366239149e-01;
    private static final double LG4 = 2.222219843214978396e-01;
    private static final double LG5 = 1.818357216161805012e-01;
    private static final double LG6 = 1.531383769920937332e-01;
    private static final double LG7 = 1.479819860511658591e-01;

    private static final double L1 = 5.99999999999994648725e-01;
    private static final double L2 = 4.28571428578550184252e-01;
    private static final double L3 = 3.33333329818377432918e-01;
    private static final double L4 = 2.72728123808534006489e-01;
    private static final double L5 = 2.30660745775561754067e-01;
    private static final double L6 = 2.06975017800338417784e-01;
    private static final double DP_H1 = 5.84962487220764160156e-01;
    private static final double DP_L1 = 1.35003920212974897128e-08;
    private static final double LG2_FULL = 6.93147180559945286227e-01;
    private static final double LG2_H = 6.93147182464599609375e-01;
    private static final double LG2_L = -1.90465429995776804525e-09;
    private static final double OVT = 8.0085662595372944372e-17;
    private static final double CP = 9.61796693925975554329e-01;
    private static final double CP_H = 9.61796700954437255859e-01;
    private static final double CP_L = -7.02846165095275826516e-09;

    // fdlibm divides by a variable zero so the C compiler can't fold the infinities and NaNs away.
    private static double zero = 0.0;

    private StrictMath() {
    }

    public static double exp(double x) {
        int hx = hi(x);
        int xsb = hx >>> 31;
        hx &= 0x7fffffff;
        double hi = 0;
        double lo = 0;
        int k = 0;
        if (hx >= 0x40862E42) {
            if (hx >= 0x7ff00000) {
                if (((hx & 0xfffff) | lo(x)) != 0) {
                    return x + x;
                }
                return xsb == 0 ? x : 0.0;
            }
            if (x > EXP_OVERFLOW) {
                return HUGE * HUGE;
            }
            if (x < EXP_UNDERFLOW) {
                return TWO_M1000 * TWO_M1000;
            }
        }
        if (hx > 0x3fd62e42) {
            if (hx < 0x3FF0A2B2) {
                hi = x - (xsb == 0 ? LN2_HI : -LN2_HI);
                lo = xsb == 0 ? LN2_LO : -LN2_LO;
                k = 1 - xsb - xsb;
            } else {
                k = (int) (INV_LN2 * x + (xsb == 0 ? 0.5 : -0.5));
                double t = k;
                hi = x - t * LN2_HI;
                lo = t * LN2_LO;
            }
            x = hi - lo;
        } else if (hx < 0x3e300000) {
            if (HUGE + x > 1.0) {
                return 1.0 + x;
            }
        }
        double t = x * x;
        double c = x - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
        if (k == 0) {
            return 1.0 - ((x * c) / (c - 2.0) - x);
        }
        double y = 1.0 - ((lo - (x * c) / (2.0 - c)) - hi);
        if (k >= -1021) {
            return withHi(y, hi(y) + (k << 20));
        }
        return withHi(y, hi(y) + ((k + 1000) << 20)) * TWO_M1000;
    }

    public static double log(double x) {
        int hx = hi(x);
        int lx = lo(x);
        int k = 0;
        if (hx < 0x00100000) {
            if (((hx & 0x7fffffff) | lx) == 0) {
                return -TWO54 / zero;
            }
            if (hx < 0) {
                return (x - x) / zero;
            }
            k -= 54;
            x *= TWO54;
            hx = hi(x);
        }
        if (hx >= 0x7ff00000) {
            return x + x;
        }
        k += (hx >> 20) - 1023;
        hx &= 0x000fffff;
        int i = (hx + 0x95f64) & 0x100000;
        x = withHi(x, hx | (i ^ 0x3ff00000));
        k += i >> 20;
        double f = x - 1.0;
        if ((0x000fffff & (2 + hx)) < 3) {
            if (f == zero) {
                if (k == 0) {
                    return zero;
                }
                double dk = k;
                return dk * LN2_HI + dk * LN2_LO;
            }
            double r = f * f * (0.5 - 0.33333333333333333 * f);
            if (k == 0) {
                return f - r;
            }
            double dk = k;
            return dk * LN2_HI - ((r - dk * LN2_LO) - f);
        }
        double s = f / (2.0 + f);
        double dk = k;
        double z = s * s;
        i = hx - 0x6147a;
        double w = z * z;
        int j = 0x6b851 - hx;
        double t1 = w * (LG2 + w * (LG4 + w * LG6));
        double t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)));
        i |= j;
        double r = t2 + t1;
        if (i > 0) {
            double hfsq = 0.5 * f * f;
            if (k == 0) {
                return f - (hfsq - s * (hfsq + r));
            }
            return dk * LN2_HI - ((hfsq - (s * (hfsq + r) + dk * LN2_LO)) - f);
        }
        if (k == 0) {
            return f - s * (f - r);
        }
        return dk * LN2_HI - ((s * (f - r) - dk * LN2_LO) - f);
    }

    public static double pow(double x, double y) {
        int hx = hi(x);
        int lx = lo(x);
        int hy = hi(y);
        int ly = lo(y);
        int ix = hx & 0x7fffffff;
        int iy = hy & 0x7fffffff;

        if ((iy | ly) == 0) {
            return 1.0;
        }
        if (ix > 0x7ff00000 || (ix == 0x7ff00000 && lx != 0) || iy > 0x7ff00000 || (iy == 0x7ff00000 && ly != 0)) {
            return x + y;
        }

        // 0 when y isn't an integer, 1 when it's odd, 2 when it's even; only worked out for x < 0.
        int yisint = 0;
        if (hx < 0) {
            if (iy >= 0x43400000) {
                yisint = 2;
            } else if (iy >= 0x3ff00000) {
                int k = (iy >> 20) - 0x3ff;
                if (k > 20) {
                    int j = ly >>> (52 - k);
                    if ((j << (52 - k)) == ly) {
                        yisint = 2 - (j & 1);
                    }
                } else if (ly == 0) {
                    int j = iy >> (20 - k);
                    if ((j << (20 - k)) == iy) {
                        yisint = 2 - (j & 1);
                    }
                }
            }
        }

        if (ly == 0) {
            if (iy == 0x7ff00000) {
                if (((ix - 0x3ff00000) | lx) == 0) {
                    return y - y;
                } else if (ix >= 0x3ff00000) {
                    return hy >= 0 ? y : zero;
                } else {
                    return hy < 0 ? -y : zero;
                }
            }
            if (iy == 0x3ff00000) {
                return hy < 0 ? 1.0 / x : x;
            }
            if (hy == 0x40000000) {
                return x * x;
            }
            if (hy == 0x3fe00000 && hx >= 0) {
                return Math.sqrt(x);
            }
        }

        double ax = Math.abs(x);
        if (lx == 0 && (ix == 0x7ff00000 || ix == 0 || ix == 0x3ff00000)) {
            double z = ax;
            if (hy < 0) {
                z = 1.0 / z;
            }
            if (hx < 0) {
                if (((ix - 0x3ff00000) | yisint) == 0) {
                    z = (z - z) / (z - z);
                } else if (yisint == 1) {
                    z = -z;
                }
            }
            return z;
        }

        int n = (hx >> 31) + 1;
        if ((n | yisint) == 0) {
            return (x - x) / (x - x);
        }
        double s = 1.0;
        if ((n | (yisint - 1)) == 0) {
            s = -1.0;
        }

        double t1;
        double t2;
        if (iy > 0x41e00000) {
            if (iy > 0x43f00000) {
                if (ix <= 0x3fefffff) {
                    return hy < 0 ? HUGE * HUGE : TINY * TINY;
                }
                if (ix >= 0x3ff00000) {
                    return hy > 0 ? HUGE * HUGE : TINY * TINY;
                }
            }
            if (ix < 0x3fefffff) {
                return hy < 0 ? s * HUGE * HUGE : s * TINY * TINY;
            }
            if (ix > 0x3ff00000) {
                return hy > 0 ? s * HUGE * HUGE : s * TINY * TINY;
            }
            double t = ax - 1.0;
            double w = (t * t) * (0.5 - t * (0.3333333333333333333333 - t * 0.25));
            double u = INV_LN2_HI * t;
            double v = t * INV_LN2_LO - w * INV_LN2;
            t1 = highWord(u + v);
            t2 = v - (t1 - u);
        } else {
            n = 0;
            if (ix < 0x00100000) {
                ax *= TWO53;
                n -= 53;
                ix = hi(ax);
            }
            n += (ix >> 20) - 0x3ff;
            int j = ix & 0x000fffff;
            int k;
            ix = j | 0x3ff00000;
            if (j <= 0x3988E) {
                k = 0;
            } else if (j < 0xBB67A) {
                k = 1;
            } else {
                k = 0;
                n += 1;
                ix -= 0x00100000;
            }
            ax = withHi(ax, ix);
            double bp = k == 0 ? 1.0 : 1.5;
            double dpH = k == 0 ? 0.0 : DP_H1;
            double dpL = k == 0 ? 0.0 : DP_L1;

            double u = ax - bp;
            double v = 1.0 / (ax + bp);
            double ss = u * v;
            double sH = highWord(ss);
            double tH = fromHi(((ix >> 1) | 0x20000000) + 0x00080000 + (k << 18));
            double tL = ax - (tH - bp);
            double sL = v * ((u - sH * tH) - sH * tL);
            double s2 = ss * ss;
            double r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6)))));
            r += sL * (sH + ss);
            s2 = sH * sH;
            tH = highWord(3.0 + s2 + r);
            tL = r - ((tH - 3.0) - s2);
            u = sH * tH;
            v = sL * tH + tL * ss;
            double pH = highWord(u + v);
            double pL = v - (pH - u);
            double zH = CP_H * pH;
            double zL = CP_L * pH + pL * CP + dpL;
            double t = n;
            t1 = highWord(((zH + zL) + dpH) + t);
            t2 = zL - (((t1 - t) - dpH) - zH);
        }

        double y1 = highWord(y);
        double pL = (y - y1) * t1 + y * t2;
        double pH = y1 * t1;
        double z = pL + pH;
        int j = hi(z);
        int i = lo(z);
        if (j >= 0x40900000) {
            if (((j - 0x40900000) | i) != 0) {
                return s * HUGE * HUGE;
            }
            if (pL + OVT > z - pH) {
                return s * HUGE * HUGE;
            }
        } else if ((j & 0x7fffffff) >= 0x4090cc00) {
            if (((j - 0xc090cc00) | i) != 0) {
                return s * TINY * TINY;
            }
            if (pL <= z - pH) {
                return s * TINY * TINY;
            }
        }

        i = j & 0x7fffffff;
        int k = (i >> 20) - 0x3ff;
        n = 0;
        if (i > 0x3fe00000) {
            n = j + (0x00100000 >> (k + 1));
            k = ((n & 0x7fffffff) >> 20) - 0x3ff;
            double t = fromHi(n & ~(0x000fffff >> k));
            n = ((n & 0x000fffff) | 0x00100000) >> (20 - k);
            if (j < 0) {
                n = -n;
            }
            pH -= t;
        }
        double t = highWord(pL + pH);
        double u = t * LG2_H;
        double v = (pL - (t - pH)) * LG2_FULL + t * LG2_L;
        z = u + v;
        double w = v - (z - u);
        t = z * z;
        t1 = z - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
        double r = (z * t1) / (t1 - 2.0) - (w + z * w);
        z = 1.0 - (r - z);
        j = hi(z) + (n << 20);
        if ((j >> 20) <= 0) {
            z = scalbn(z, n);
        } else {
            z = withHi(z, j);
        }
        return s * z;
    }

    public static double sin(double a) {
        return Trigonometry.sin(a);
    }

    public static double cos(double a) {
        return Trigonometry.cos(a);
    }

    public static double tan(double a) {
        return Trigonometry.tan(a);
    }

    public static double asin(double a) {
        return Trigonometry.asin(a);
    }

    public static double acos(double a) {
        return Trigonometry.acos(a);
    }

    public static double atan(double a) {
        return Trigonometry.atan(a);
    }

    public static double atan2(double y, double x) {
        return Trigonometry.atan2(y, x);
    }

    static double scalbn(double x, int n) {
        int hx = hi(x);
        int lx = lo(x);
        int k = (hx & 0x7ff00000) >> 20;
        if (k == 0) {
            if ((lx | (hx & 0x7fffffff)) == 0) {
                return x;
            }
            x *= TWO54;
            hx = hi(x);
            k = ((hx & 0x7ff00000) >> 20) - 54;
            if (n < -50000) {
                return TINY * x;
            }
        }
        if (k == 0x7ff) {
            return x + x;
        }
        k += n;
        if (k > 0x7fe) {
            return HUGE * Math.copySign(HUGE, x);
        }
        if (k > 0) {
            return withHi(x, (hx & 0x800fffff) | (k << 20));
        }
        if (k <= -54) {
            if (n > 50000) {
                return HUGE * Math.copySign(HUGE, x);
            }
            return TINY * Math.copySign(TINY, x);
        }
        k += 54;
        return withHi(x, (hx & 0x800fffff) | (k << 20)) * TWO_M54;
    }

    static int hi(double x) {
        return (int) (Double.doubleToRawLongBits(x) >> 32);
    }

    static int lo(double x) {
        return (int) Double.doubleToRawLongBits(x);
    }

    static double withHi(double x, int hi) {
        return Double.longBitsToDouble((long) hi << 32 | Double.doubleToRawLongBits(x) & 0xffffffffL);
    }

    static double fromHi(int hi) {
        return Double.longBitsToDouble((long) hi << 32);
    }

    /** x with its low word cleared. */
    static double highWord(double x) {
        return Double.longBitsToDouble(Double.doubleToRawLongBits(x) & 0xffffffff00000000L);
    }
}
