package org.quimera.labs.financial.sac;

import org.quimera.labs.financial.engine.QDec128;

/**
 * Critério de projeção / arredondamento contábil da fibra interna E_16 (10^-16)
 * para a fibra monetária E_2 (centavos de Real).
 */
public enum SacRoundingMode {
    /**
     * Arredondamento Bancário ABNT NBR 5891 (Half-Even / Par mais próximo).
     */
    ABNT_HALF_EVEN {
        @Override
        public long toCents(QDec128 value) {
            return value.projectCentsRound2HalfEven();
        }
    },

    /**
     * Arredondamento Simétrico BACEN (Half-Up).
     */
    BACEN_HALF_UP {
        @Override
        public long toCents(QDec128 value) {
            return value.projectCentsRound2HalfUp();
        }
    },

    /**
     * Truncamento Estrito ( Floor absoluto na 2ª casa decimal).
     */
    B3_TRUNCATE {
        @Override
        public long toCents(QDec128 value) {
            return value.projectCentsTrunc2();
        }
    };

    public abstract long toCents(QDec128 value);
}
