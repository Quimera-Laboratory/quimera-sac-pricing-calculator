package org.quimera.labs.financial.sac;

import org.quimera.labs.financial.engine.QDec128;

import java.util.Arrays;
import java.util.Objects;

/**
 * Especificação contratual de uma operação SAC (Sistema de Amortização Constante)
 * parametrizada por qualquer base de contagem de dias ({@link DayCountBasis#BUS_252},
 * {@link DayCountBasis#ACT_360} ou {@link DayCountBasis#ACT_365}) e avaliada em Q128.
 */
public final class SacContractSpec {

    private final long principalCents;
    private final int totalInstallments;
    private final int graceInstallments;
    private final DayCountBasis dayCountBasis;
    private final QDec128 annualContractRate;
    private final int[] daysPerInstallment;
    private final long[] monetaryCorrectionRates16;
    private final RateCompoundingMode compoundingMode;
    private final SacRoundingMode roundingMode;

    private SacContractSpec(Builder builder) {
        if (builder.principalCents <= 0L) {
            throw new IllegalArgumentException("O principal deve ser maior que zero centavos.");
        }
        if (builder.totalInstallments <= 0) {
            throw new IllegalArgumentException("O número total de parcelas deve ser maior que zero.");
        }
        if (builder.graceInstallments < 0 || builder.graceInstallments >= builder.totalInstallments) {
            throw new IllegalArgumentException(
                "A carência deve ser >= 0 e menor que o total de parcelas (" + builder.totalInstallments + ")."
            );
        }
        this.principalCents = builder.principalCents;
        this.totalInstallments = builder.totalInstallments;
        this.graceInstallments = builder.graceInstallments;
        this.dayCountBasis = Objects.requireNonNull(builder.dayCountBasis, "dayCountBasis não pode ser nulo");
        this.annualContractRate = Objects.requireNonNull(builder.annualContractRate, "annualContractRate não pode ser nulo");
        this.compoundingMode = Objects.requireNonNull(builder.compoundingMode, "compoundingMode não pode ser nulo");
        this.roundingMode = Objects.requireNonNull(builder.roundingMode, "roundingMode não pode ser nulo");

        if (builder.daysPerInstallment != null) {
            if (builder.daysPerInstallment.length != builder.totalInstallments) {
                throw new IllegalArgumentException(
                    "O vetor daysPerInstallment (" + builder.daysPerInstallment.length +
                    ") deve ter o mesmo tamanho de totalInstallments (" + builder.totalInstallments + ")."
                );
            }
            this.daysPerInstallment = Arrays.copyOf(builder.daysPerInstallment, builder.totalInstallments);
        } else {
            this.daysPerInstallment = new int[builder.totalInstallments];
            Arrays.fill(this.daysPerInstallment, this.dayCountBasis.getDefaultDaysPerMonth());
        }

        if (builder.monetaryCorrectionRates16 != null) {
            if (builder.monetaryCorrectionRates16.length != builder.totalInstallments) {
                throw new IllegalArgumentException(
                    "O vetor monetaryCorrectionRates16 deve ter tamanho igual a totalInstallments."
                );
            }
            this.monetaryCorrectionRates16 = Arrays.copyOf(builder.monetaryCorrectionRates16, builder.totalInstallments);
        } else {
            this.monetaryCorrectionRates16 = new long[builder.totalInstallments];
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public long getPrincipalCents() {
        return principalCents;
    }

    public QDec128 getPrincipalQ128() {
        return Q128RateEngine.fromCents(principalCents);
    }

    public int getTotalInstallments() {
        return totalInstallments;
    }

    public int getGraceInstallments() {
        return graceInstallments;
    }

    public DayCountBasis getDayCountBasis() {
        return dayCountBasis;
    }

    public QDec128 getAnnualContractRate() {
        return annualContractRate;
    }

    public long getAnnualContractRateRaw16() {
        return Q128RateEngine.toRaw16(annualContractRate);
    }

    public int getDaysForInstallment(int installmentIndexZeroBased) {
        return daysPerInstallment[installmentIndexZeroBased];
    }

    public int[] getDaysPerInstallment() {
        return Arrays.copyOf(daysPerInstallment, daysPerInstallment.length);
    }

    public long getMonetaryCorrectionRate16(int installmentIndexZeroBased) {
        return monetaryCorrectionRates16[installmentIndexZeroBased];
    }

    public RateCompoundingMode getCompoundingMode() {
        return compoundingMode;
    }

    public SacRoundingMode getRoundingMode() {
        return roundingMode;
    }

    public static final class Builder {
        private long principalCents;
        private int totalInstallments = 12;
        private int graceInstallments = 0;
        private DayCountBasis dayCountBasis = DayCountBasis.BUS_252;
        private QDec128 annualContractRate = Q128RateEngine.fromString("0.12");
        private int[] daysPerInstallment;
        private long[] monetaryCorrectionRates16;
        private RateCompoundingMode compoundingMode = RateCompoundingMode.EXPONENTIAL_COMPOUND;
        private SacRoundingMode roundingMode = SacRoundingMode.ABNT_HALF_EVEN;

        private Builder() {
        }

        public Builder principalCents(long principalCents) {
            this.principalCents = principalCents;
            return this;
        }

        public Builder principalReais(String reaisDecimal) {
            QDec128 dec = Q128RateEngine.fromString(reaisDecimal);
            this.principalCents = dec.projectCentsRound2HalfEven();
            return this;
        }

        public Builder totalInstallments(int totalInstallments) {
            this.totalInstallments = totalInstallments;
            return this;
        }

        public Builder graceInstallments(int graceInstallments) {
            this.graceInstallments = graceInstallments;
            return this;
        }

        public Builder dayCountBasis(DayCountBasis dayCountBasis) {
            this.dayCountBasis = dayCountBasis;
            return this;
        }

        /**
         * Define a base informando diretamente 252, 360 ou 365.
         */
        public Builder dayCountBasis(int daysInYear) {
            this.dayCountBasis = DayCountBasis.fromDaysInYear(daysInYear);
            return this;
        }

        public Builder annualContractRate(QDec128 annualContractRate) {
            this.annualContractRate = annualContractRate.toInternal16();
            return this;
        }

        /**
         * Define a taxa efetiva anual em formato decimal (ex: "0.126825" para 12,6825% a.a.).
         */
        public Builder annualContractRate(String annualRateDecimal) {
            this.annualContractRate = Q128RateEngine.fromString(annualRateDecimal);
            return this;
        }

        /**
         * Define um número uniforme de dias por parcela (ex: 21 DU na base 252 ou 30 DC em 360/365).
         */
        public Builder uniformDaysPerInstallment(int daysPerPeriod) {
            if (daysPerPeriod <= 0) {
                throw new IllegalArgumentException("daysPerPeriod deve ser > 0");
            }
            this.daysPerInstallment = new int[this.totalInstallments];
            Arrays.fill(this.daysPerInstallment, daysPerPeriod);
            return this;
        }

        /**
         * Define prazos customizados (irregulares ou exatos do calendário civil/útil) por parcela.
         */
        public Builder daysPerInstallment(int[] daysPerInstallment) {
            this.daysPerInstallment = daysPerInstallment != null
                    ? Arrays.copyOf(daysPerInstallment, daysPerInstallment.length)
                    : null;
            return this;
        }

        /**
         * Define taxas de atualização monetária (ex: IPCA / IGP-M / TR) em escala 16 por período.
         */
        public Builder monetaryCorrectionRates16(long[] monetaryCorrectionRates16) {
            this.monetaryCorrectionRates16 = monetaryCorrectionRates16 != null
                    ? Arrays.copyOf(monetaryCorrectionRates16, monetaryCorrectionRates16.length)
                    : null;
            return this;
        }

        public Builder compoundingMode(RateCompoundingMode compoundingMode) {
            this.compoundingMode = compoundingMode;
            return this;
        }

        public Builder roundingMode(SacRoundingMode roundingMode) {
            this.roundingMode = roundingMode;
            return this;
        }

        public SacContractSpec build() {
            return new SacContractSpec(this);
        }
    }
}
