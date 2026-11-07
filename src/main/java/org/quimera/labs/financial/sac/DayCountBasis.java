package org.quimera.labs.financial.sac;

/**
 * Convenções de Base de Contagem de Dias (Day-Count Basis) suportadas pela Calculadora SAC Q128.
 * <ul>
 *   <li>{@link #BUS_252}: Base 252 Dias Úteis (Padrão B3 / BACEN / ANBIMA para DI, SELIC e Contratos Brasil).</li>
 *   <li>{@link #ACT_360}: Base 360 Dias Corridos / Comercial (Actual/360 ou 30/360).</li>
 *   <li>{@link #ACT_365}: Base 365 Dias Corridos (Actual/365 Fixo).</li>
 * </ul>
 */
public enum DayCountBasis {

    BUS_252(252, 21, "DU/252 (Dias Úteis B3/BACEN)"),
    ACT_360(360, 30, "ACT/360 (Ano Comercial 360 Dias)"),
    ACT_365(365, 30, "ACT/365 (Ano Civil Fixo 365 Dias)");

    private final int daysInYear;
    private final int defaultDaysPerMonth;
    private final String description;

    DayCountBasis(int daysInYear, int defaultDaysPerMonth, String description) {
        this.daysInYear = daysInYear;
        this.defaultDaysPerMonth = defaultDaysPerMonth;
        this.description = description;
    }

    public int getDaysInYear() {
        return daysInYear;
    }

    public int getDefaultDaysPerMonth() {
        return defaultDaysPerMonth;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Obtém a base a partir do número inteiro de dias no ano (252, 360 ou 365).
     */
    public static DayCountBasis fromDaysInYear(int days) {
        return switch (days) {
            case 252 -> BUS_252;
            case 360 -> ACT_360;
            case 365 -> ACT_365;
            default -> throw new IllegalArgumentException(
                "Base de dias inválida: " + days + ". Valores suportados: 252, 360 ou 365."
            );
        };
    }
}
