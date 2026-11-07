package org.quimera.labs.financial.sac;

/**
 * Regime de capitalização da taxa de juros no cálculo do fator de período e desconto.
 */
public enum RateCompoundingMode {
    /**
     * Capitalização Exponencial Composta (Padrão B3 / BACEN / Mercado Financeiro):
     * F(d, B) = (1 + i_a)^(d / B) = exp((d / B) * ln(1 + i_a)) em Q128.
     */
    EXPONENTIAL_COMPOUND,

    /**
     * Capitalização Linear / Proporcional (Juros Simples Comercial):
     * F(d, B) = 1 + i_a * (d / B) em Q128.
     */
    LINEAR_PROPORTIONAL
}
