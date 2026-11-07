package org.quimera.labs.financial.sac;

import org.quimera.labs.financial.engine.QDec128;
import org.quimera.labs.financial.engine.QScale;
import org.quimera.labs.financial.engine.QuimeraCategoricalDecimal;

import java.math.BigDecimal;

/**
 * Motor Algébrico Q128 para Java: encapsula as operações de {@link QuimeraCategoricalDecimal}
 * e {@link QDec128} com semântica idiomática Java e cálculo de fatores de capitalização e
 * desconto para qualquer base de dias ({@link DayCountBasis#BUS_252}, {@link DayCountBasis#ACT_360},
 * {@link DayCountBasis#ACT_365}).
 */
public final class Q128RateEngine {

    /** Base da fibra interna E_16: 10^16 */
    public static final long BASE_16 = 10_000_000_000_000_000L;

    /** Escala interna padrão (16 casas decimais) */
    public static final byte SCALE_16 = (byte) 16;

    /** Escala monetária BACEN (2 casas decimais - centavos) */
    public static final byte SCALE_CENTS = QScale.CENTS();

    /** Escala de fatores B3 (8 casas decimais) */
    public static final byte SCALE_B3_FACTOR = QScale.B3_FACTOR();

    public static final QDec128 ZERO = new QDec128(0L, 0L, SCALE_16);
    public static final QDec128 ONE  = new QDec128(1L, 0L, SCALE_16);

    private Q128RateEngine() {
    }

    // =========================================================================
    // CONSTRUTORES E CONVERSORES Q128 PARA JAVA
    // =========================================================================

    public static QDec128 fromCents(long cents) {
        return QDec128.fromCents(cents).toInternal16();
    }

    public static QDec128 fromLong(long value) {
        return new QDec128(value, 0L, SCALE_16);
    }

    public static QDec128 fromString(String decimalStr) {
        return QDec128.fromString(decimalStr, SCALE_16);
    }

    public static QDec128 fromBigDecimal(BigDecimal bd) {
        return QDec128.fromBigDecimal(bd, SCALE_16);
    }

    /**
     * Converte um fator bruto em escala 16 (raw16 = q * 10^16 + r) para {@link QDec128}.
     */
    public static QDec128 fromRaw16(long raw16) {
        long quo = raw16 / BASE_16;
        long rem = raw16 - (quo * BASE_16);
        if (rem < 0L) {
            quo -= 1L;
            rem += BASE_16;
        }
        return new QDec128(quo, rem, SCALE_16);
    }

    /**
     * Extrai a representação compacta de 64 bits em escala 16 (q * 10^16 + r)
     * para taxas e fatores onde |q| <= 900.
     */
    public static long toRaw16(QDec128 dec) {
        QDec128 d16 = dec.scale() == SCALE_16 ? dec : dec.toInternal16();
        return Math.addExact(Math.multiplyExact(d16.q(), BASE_16), d16.r());
    }

    // =========================================================================
    // OPERAÇÕES ARITMÉTICAS Q128 IDIOMÁTICAS EM JAVA
    // =========================================================================

    public static QDec128 add(QDec128 a, QDec128 b) {
        return a.$plus(b);
    }

    public static QDec128 subtract(QDec128 a, QDec128 b) {
        return a.$minus(b);
    }

    public static QDec128 multiply(QDec128 a, QDec128 b) {
        return a.$times(b, SCALE_16);
    }

    public static QDec128 multiplyByFactor16(QDec128 amount, long factorRaw16) {
        return amount.$times(fromRaw16(factorRaw16), SCALE_16);
    }

    public static QDec128 divide(QDec128 a, QDec128 b) {
        return a.$div(b, SCALE_16);
    }

    public static QDec128 divideByLong(QDec128 a, long divisor) {
        return a.$div(divisor);
    }

    public static QDec128 negate(QDec128 a) {
        return a.unary_$minus();
    }

    public static QDec128 abs(QDec128 a) {
        return a.abs();
    }

    // =========================================================================
    // MOTOR DE TAXAS E FATORES EM QUALQUER BASE (252, 360, 365) VIA Q128
    // =========================================================================

    /**
     * Calcula de forma exata (value * numerator) / denominator com arredondamento Half-Even
     * usando aritmética de 128 bits sem alocação de objetos na Heap.
     */
    public static long mulDivRoundHalfEven(long value, long numerator, long denominator) {
        if (denominator <= 0L) {
            throw new IllegalArgumentException("Denominator must be positive: " + denominator);
        }
        if (value == 0L || numerator == 0L) {
            return 0L;
        }
        boolean isNeg = (value < 0L) ^ (numerator < 0L);
        long aVal = Math.abs(value);
        long aNum = Math.abs(numerator);

        long pHi = Math.multiplyHigh(aVal, aNum);
        long pLo = aVal * aNum;

        long quot = QuimeraCategoricalDecimal.div128By64Unsigned(pHi, pLo, denominator);
        long rem = pLo - (quot * denominator);
        long twiceRem = rem << 1;

        if (twiceRem > denominator || (twiceRem == denominator && (quot & 1L) != 0L)) {
            quot += 1L;
        }
        return isNeg ? -quot : quot;
    }

    /**
     * Calcula a taxa logarítmica contínua diária na base informada (252, 360 ou 365):
     *   lambda_dia = ln(1 + i_anual) / B
     * avaliada na fibra E_16 com Zero Heap Allocation.
     */
    public static long dailyContinuousLogRate16(
            long annualEffectiveRateRaw16,
            DayCountBasis basis) {
        if (annualEffectiveRateRaw16 == 0L) {
            return 0L;
        }
        long annualLog16 = QuimeraCategoricalDecimal.log1pFactor64(annualEffectiveRateRaw16);
        return mulDivRoundHalfEven(annualLog16, 1L, basis.getDaysInYear());
    }

    /**
     * Calcula o fator de capitalização para {@code days} dias na base especificada
     * ({@link DayCountBasis#BUS_252}, {@link DayCountBasis#ACT_360} ou {@link DayCountBasis#ACT_365}):
     * <ul>
     *   <li>No modo {@link RateCompoundingMode#EXPONENTIAL_COMPOUND}:
     *       F(d, B) = (1 + i_a)^(d / B) = exp((d / B) * ln(1 + i_a)) via Q128.</li>
     *   <li>No modo {@link RateCompoundingMode#LINEAR_PROPORTIONAL}:
     *       F(d, B) = 1 + i_a * (d / B) via Q128.</li>
     * </ul>
     *
     * @param annualRateRaw16 Taxa efetiva anual em escala 16 (ex: 12% a.a. = 0.12 * 10^16).
     * @param days            Número de dias no período (dias úteis na base 252 ou corridos em 360/365).
     * @param basis           Base de contagem de dias (252, 360 ou 365).
     * @param mode            Regime de capitalização (Exponencial ou Linear).
     * @return Fator acumulado em escala 16 (1.0 = BASE_16).
     */
    public static long periodCompoundFactor16(
            long annualRateRaw16,
            int days,
            DayCountBasis basis,
            RateCompoundingMode mode) {
        if (days < 0) {
            throw new IllegalArgumentException("Days cannot be negative: " + days);
        }
        if (days == 0 || annualRateRaw16 == 0L) {
            return BASE_16;
        }

        int daysInYear = basis.getDaysInYear();

        if (mode == RateCompoundingMode.LINEAR_PROPORTIONAL) {
            long periodLinearRate16 = mulDivRoundHalfEven(annualRateRaw16, days, daysInYear);
            return Math.addExact(BASE_16, periodLinearRate16);
        }

        // Capitalização Exponencial Composta em Q128:
        // Decompõe days = fullYears * B + remDays para preservar identidade exata em anos inteiros
        int fullYears = days / daysInYear;
        int remDays = days % daysInYear;

        long annualFactor16 = Math.addExact(BASE_16, annualRateRaw16);
        long fullYearsFactor16 = fullYears > 0
                ? QuimeraCategoricalDecimal.powFactor64(annualFactor16, fullYears)
                : BASE_16;

        if (remDays == 0) {
            return fullYearsFactor16;
        }

        long annualLog16 = QuimeraCategoricalDecimal.log1pFactor64(annualRateRaw16);
        long remLog16 = mulDivRoundHalfEven(annualLog16, remDays, daysInYear);
        long remFactor16 = QuimeraCategoricalDecimal.expFactor64(remLog16);

        return fullYears > 0
                ? QuimeraCategoricalDecimal.signedMonoidMul16Safe(fullYearsFactor16, remFactor16)
                : remFactor16;
    }

    /**
     * Calcula o fator de desconto a Valor Presente (PV Discount Factor) para {@code cumulativeDays}
     * dias na base especificada (252, 360 ou 365):
     * <ul>
     *   <li>Exponencial: D(d, B) = (1 + i_m)^(-d / B) = exp(-(d / B) * ln(1 + i_m)) em Q128.</li>
     *   <li>Linear:      D(d, B) = 1 / (1 + i_m * (d / B)) em Q128.</li>
     * </ul>
     */
    public static long discountFactor16(
            long marketAnnualRateRaw16,
            int cumulativeDays,
            DayCountBasis basis,
            RateCompoundingMode mode) {
        if (cumulativeDays < 0) {
            throw new IllegalArgumentException("Cumulative days cannot be negative: " + cumulativeDays);
        }
        if (cumulativeDays == 0 || marketAnnualRateRaw16 == 0L) {
            return BASE_16;
        }

        if (mode == RateCompoundingMode.LINEAR_PROPORTIONAL) {
            long accFactor16 = periodCompoundFactor16(marketAnnualRateRaw16, cumulativeDays, basis, mode);
            return QuimeraCategoricalDecimal.divFactor64Safe(BASE_16, accFactor16);
        }

        int daysInYear = basis.getDaysInYear();
        long annualLog16 = QuimeraCategoricalDecimal.log1pFactor64(marketAnnualRateRaw16);
        long negPeriodLog16 = -mulDivRoundHalfEven(annualLog16, cumulativeDays, daysInYear);
        return QuimeraCategoricalDecimal.expFactor64(negPeriodLog16);
    }

    /**
     * Converte uma taxa efetiva anual expressa na base {@code fromBasis} (para uma janela de
     * {@code fromDays} dias) na taxa efetiva anual equivalente na base {@code toBasis}
     * (para uma janela de {@code toDays} dias) mantendo o mesmo fator financeiro de período:
     *   (1 + i_from)^(fromDays / fromBasis) == (1 + i_to)^(toDays / toBasis)
     */
    public static QDec128 convertEquivalentAnnualRate(
            QDec128 annualRateFrom,
            DayCountBasis fromBasis,
            int fromDays,
            DayCountBasis toBasis,
            int toDays) {
        if (fromDays <= 0 || toDays <= 0) {
            throw new IllegalArgumentException("Days must be positive");
        }
        long rateFrom16 = toRaw16(annualRateFrom);
        long logFrom16 = QuimeraCategoricalDecimal.log1pFactor64(rateFrom16);
        // logPeriod = logFrom * (fromDays / fromBasis)
        // logTo     = logPeriod * (toBasis / toDays) = logFrom * (fromDays * toBasis) / (fromBasis * toDays)
        long numerator = (long) fromDays * toBasis.getDaysInYear();
        long denominator = (long) fromBasis.getDaysInYear() * toDays;
        long logTo16 = mulDivRoundHalfEven(logFrom16, numerator, denominator);
        long factorTo16 = QuimeraCategoricalDecimal.expFactor64(logTo16);
        return fromRaw16(factorTo16 - BASE_16);
    }

    /**
     * Formata um valor em centavos para moeda BRL (ex: "R$ 1.234.567,89").
     */
    public static String formatCentsAsBrl(long cents) {
        boolean neg = cents < 0L;
        long abs = Math.abs(cents);
        long reais = abs / 100L;
        long centavos = abs % 100L;
        String reaisFormatted = String.format(java.util.Locale.ITALY, "%,d", reais);
        return (neg ? "-R$ " : "R$ ") + reaisFormatted + String.format(",%02d", centavos);
    }
}
