package org.quimera.labs.financial.sac.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.quimera.labs.financial.engine.QDec128;
import org.quimera.labs.financial.engine.QuimeraCategoricalDecimal;
import org.quimera.labs.financial.sac.Q128RateEngine;
import scala.Tuple2;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark Oficial OpenJDK JMH 1.37: Confronto {@code exp(10000000, 42)} entre
 * {@link QuimeraCategoricalDecimal} / {@link QDec128} (Q128) e {@link BigDecimal} ({@code DECIMAL128}).
 *
 * <p>Cobre as 3 interpretações matemáticas e financeiras de {@code exp(10000000, 42)}:
 * <ol>
 *   <li><b>Cenário 1 — Capitalização Exponencial Contínua sobre {@code R$ 10.000.000,42} ({@code 10000000,42})</b>:
 *       Cálculo de \( S_0 \cdot \exp(42 \cdot \ln(1 + i)) \) para principal \( S_0 = 10.000.000,42 \).</li>
 *   <li><b>Cenário 2 — Exponenciação Inteira {@code exp(base, 42)} / {@code pow(fator, 42)} aplicada a {@code 10.000.000}</b>:
 *       Cálculo de \( 10.000.000 \times (1 + i)^{42} \) via exponenciação binária de 128 bits vs {@code BigDecimal.pow(42, DECIMAL128)}.</li>
 *   <li><b>Cenário 3 — Exponencial Direta Extrema {@code exp(42)} (\(e^{42} \approx 1,739274941520501 \times 10^{18}\))</b>:
 *       Avaliação no teto do registrador de 64 bits com sinal (\(2^{63}-1 \approx 9,223 \times 10^{18}\)) via decomposição
 *       algébrica de Euler/Horner em Q128 vs Série de Taylor/Horner em {@code BigDecimal(DECIMAL128)}.</li>
 * </ol>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms1G", "-Xmx1G", "-XX:+UseG1GC"})
public class Q128Exp42JmhBenchmark {

    private static final long BASE_16 = Q128RateEngine.BASE_16; // 10^16
    private static final byte SCALE_16 = Q128RateEngine.SCALE_16;
    private static final MathContext MC128 = MathContext.DECIMAL128;

    // Principal: R$ 10.000.000,42 (10000000,42)
    private long principalQ;
    private long principalR;
    private QDec128 qdecPrincipal;
    private BigDecimal bdPrincipal;

    // Principal inteiro: R$ 10.000.000,00
    private long principal10MQ;
    private long principal10MR;
    private QDec128 qdecPrincipal10M;
    private BigDecimal bdPrincipal10M;

    // Taxa efetiva: equivalente mensal de 14,7931% a.a. (i_m = 0.0054715808312491) e n = 42 períodos
    private int exponent42;
    private long netRateScaled16;
    private long rateFactorScaled16;
    private long lnRateFactorScaled16;
    private QDec128 qdecRateFactor;
    private BigDecimal bdRateFactor;
    private BigDecimal bdLnRateFactor;

    // Argumento extremo x = 42.0 para exp(42)
    private long expArg42Q;
    private long expArg42R;
    private BigDecimal bdExpArg42;

    @Setup(Level.Trial)
    public void setup() {
        this.exponent42 = 42;

        // 10.000.000,42 em Q128: q = 10_000_000L, r = 0.42 * 10^16 = 4_200_000_000_000_000L
        this.principalQ = 10_000_000L;
        this.principalR = 4_200_000_000_000_000L;
        this.qdecPrincipal = new QDec128(principalQ, principalR, SCALE_16);
        this.bdPrincipal = new BigDecimal("10000000.42");

        // 10.000.000,00
        this.principal10MQ = 10_000_000L;
        this.principal10MR = 0L;
        this.qdecPrincipal10M = new QDec128(principal10MQ, principal10MR, SCALE_16);
        this.bdPrincipal10M = new BigDecimal("10000000.00");

        // Fator (1 + i) = 1.0054715808312491
        this.netRateScaled16 = 54_715_808_312_491L;
        this.rateFactorScaled16 = BASE_16 + netRateScaled16;
        this.lnRateFactorScaled16 = QuimeraCategoricalDecimal.log1pFactor64(netRateScaled16);
        this.qdecRateFactor = new QDec128(1L, netRateScaled16, SCALE_16);
        this.bdRateFactor = new BigDecimal("1.0054715808312491", MC128);
        this.bdLnRateFactor = bigDecimalLog1p(bdRateFactor.subtract(BigDecimal.ONE, MC128));

        // x = 42.0
        this.expArg42Q = 42L;
        this.expArg42R = 0L;
        this.bdExpArg42 = new BigDecimal("42", MC128);
    }

    // =========================================================================
    // CENÁRIO 1: Capitalização Exponencial exp(42 * ln(1+i)) sobre 10.000.000,42
    // =========================================================================

    @Benchmark
    public long cen1_q128Primitive_exp_10000000_42() {
        long exponentScaled16 = exponent42 * lnRateFactorScaled16;
        long factorScaled16 = QuimeraCategoricalDecimal.expFactor64(exponentScaled16);
        Tuple2<Object, Object> compounded = QuimeraCategoricalDecimal.mul128Trunc16(
                principalQ, principalR,
                factorScaled16 / BASE_16, factorScaled16 % BASE_16);
        return compounded._1$mcJ$sp() ^ compounded._2$mcJ$sp();
    }

    @Benchmark
    public void cen1_qDec128Boxed_exp_10000000_42(Blackhole bh) {
        long exponentScaled16 = exponent42 * lnRateFactorScaled16;
        QDec128 expVal = new QDec128(exponentScaled16 / BASE_16, exponentScaled16 % BASE_16, SCALE_16).exp();
        QDec128 result = qdecPrincipal.$times(expVal, SCALE_16);
        bh.consume(result);
    }

    @Benchmark
    public void cen1_bigDecimal128_exp_10000000_42(Blackhole bh) {
        BigDecimal exponent = bdLnRateFactor.multiply(BigDecimal.valueOf(exponent42), MC128);
        BigDecimal factor = bigDecimalExpSmall(exponent);
        BigDecimal result = bdPrincipal.multiply(factor, MC128).setScale(2, RoundingMode.HALF_EVEN);
        bh.consume(result);
    }

    // =========================================================================
    // CENÁRIO 2: Exponenciação exp(base, 42) / pow(fator, 42) sobre 10.000.000
    // =========================================================================

    @Benchmark
    public long cen2_q128Primitive_pow42_on_10000000() {
        long factorPow42 = QuimeraCategoricalDecimal.powFactor64(rateFactorScaled16, exponent42);
        Tuple2<Object, Object> res = QuimeraCategoricalDecimal.mul128Trunc16(
                principal10MQ, principal10MR,
                factorPow42 / BASE_16, factorPow42 % BASE_16);
        return res._1$mcJ$sp() ^ res._2$mcJ$sp();
    }

    @Benchmark
    public void cen2_qDec128Boxed_pow42_on_10000000(Blackhole bh) {
        QDec128 pow42 = qdecRateFactor.pow(exponent42);
        QDec128 res = qdecPrincipal10M.$times(pow42, SCALE_16);
        bh.consume(res);
    }

    @Benchmark
    public void cen2_bigDecimal128_pow42_on_10000000(Blackhole bh) {
        BigDecimal pow42 = bdRateFactor.pow(exponent42, MC128);
        BigDecimal res = bdPrincipal10M.multiply(pow42, MC128).setScale(2, RoundingMode.HALF_EVEN);
        bh.consume(res);
    }

    // =========================================================================
    // CENÁRIO 3: Exponencial Direta Extrema exp(42.0) = e^42 (~1.73927494 x 10^18)
    // =========================================================================

    @Benchmark
    public long cen3_q128Primitive_exp42_extreme() {
        Tuple2<Object, Object> e42 = QuimeraCategoricalDecimal.exp128(expArg42Q, expArg42R);
        return e42._1$mcJ$sp() ^ e42._2$mcJ$sp();
    }

    @Benchmark
    public void cen3_bigDecimal128_exp42_extreme(Blackhole bh) {
        BigDecimal e42 = bigDecimalExpGeneral(bdExpArg42);
        bh.consume(e42);
    }

    // =========================================================================
    // IMPLEMENTAÇÕES DE REFERÊNCIA BIGDECIMAL (TAYLOR / REDUÇÃO DE ARGUMENTO)
    // =========================================================================

    /**
     * Série de Taylor em {@code MathContext.DECIMAL128} para argumentos pequenos \( |x| < 1 \).
     */
    public static BigDecimal bigDecimalExpSmall(BigDecimal x) {
        BigDecimal sum = BigDecimal.ONE;
        BigDecimal term = BigDecimal.ONE;
        for (int k = 1; k <= 28; k++) {
            term = term.multiply(x, MC128).divide(BigDecimal.valueOf(k), MC128);
            sum = sum.add(term, MC128);
            if (term.abs().compareTo(new BigDecimal("1E-33")) < 0) {
                break;
            }
        }
        return sum;
    }

    /**
     * Exponencial geral em {@code MathContext.DECIMAL128} para argumentos grandes como \( x = 42 \),
     * usando redução de argumento \( \exp(x) = (\exp(x / 2^6))^{64} \) seguida de Taylor.
     */
    public static BigDecimal bigDecimalExpGeneral(BigDecimal x) {
        BigDecimal reduced = x.divide(BigDecimal.valueOf(64), MC128);
        BigDecimal expReduced = bigDecimalExpSmall(reduced);
        return expReduced.pow(64, MC128);
    }

    /**
     * Logaritmo natural \(\ln(1 + u)\) via série de Taylor em {@code MathContext.DECIMAL128}.
     */
    public static BigDecimal bigDecimalLog1p(BigDecimal u) {
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal power = u;
        for (int k = 1; k <= 28; k++) {
            BigDecimal term = power.divide(BigDecimal.valueOf(k), MC128);
            if ((k & 1) == 1) {
                sum = sum.add(term, MC128);
            } else {
                sum = sum.subtract(term, MC128);
            }
            power = power.multiply(u, MC128);
            if (term.abs().compareTo(new BigDecimal("1E-33")) < 0) {
                break;
            }
        }
        return sum;
    }

    // =========================================================================
    // RUNNER COM AUDITORIA NUMÉRICA PRÉVIA + PERFILAMENTO JMH + GC
    // =========================================================================

    public static void main(String[] args) throws Exception {
        Q128Exp42JmhBenchmark probe = new Q128Exp42JmhBenchmark();
        probe.setup();

        System.out.println("==========================================================================================================");
        System.out.println("  AUDITORIA DE PARIDADE NUMÉRICA PRÉ-JMH: exp(10000000, 42) — Q128 vs BigDecimal (DECIMAL128)");
        System.out.println("==========================================================================================================");

        // Auditoria Cenário 1: 10.000.000,42 * exp(42 * ln(1+i))
        long expScaled16 = probe.exponent42 * probe.lnRateFactorScaled16;
        long factorScaled16 = QuimeraCategoricalDecimal.expFactor64(expScaled16);
        Tuple2<Object, Object> q128Cen1 = QuimeraCategoricalDecimal.mul128Trunc16(
                probe.principalQ, probe.principalR,
                factorScaled16 / BASE_16, factorScaled16 % BASE_16);
        QDec128 qdecCen1 = new QDec128(q128Cen1._1$mcJ$sp(), q128Cen1._2$mcJ$sp(), SCALE_16);
        long q128Cen1Cents = qdecCen1.projectCentsRound2HalfEven();

        BigDecimal bdExp1 = bigDecimalExpSmall(probe.bdLnRateFactor.multiply(BigDecimal.valueOf(42), MC128));
        BigDecimal bdCen1Full = probe.bdPrincipal.multiply(bdExp1, MC128);
        BigDecimal bdCen1Rounded = bdCen1Full.setScale(2, RoundingMode.HALF_EVEN);

        System.out.printf("  [Cenário 1] 10.000.000,42 * exp(42 * ln(1+i)):%n");
        System.out.printf("    -> Q128 (16 casas)       : %s%n", qdecCen1.toString());
        System.out.printf("    -> BigDecimal (DECIMAL128): %s%n", bdCen1Full.setScale(16, RoundingMode.HALF_EVEN).toPlainString());
        System.out.printf("    -> Q128 (ABNT R_2)       : %s%n", Q128RateEngine.formatCentsAsBrl(q128Cen1Cents));
        System.out.printf("    -> BigDecimal (ABNT R_2) : R$ %s%n", bdCen1Rounded.toPlainString());

        // Auditoria Cenário 2: 10.000.000,00 * pow(1+i, 42)
        long pow42Scaled16 = QuimeraCategoricalDecimal.powFactor64(probe.rateFactorScaled16, 42);
        Tuple2<Object, Object> q128Cen2 = QuimeraCategoricalDecimal.mul128Trunc16(
                probe.principal10MQ, probe.principal10MR,
                pow42Scaled16 / BASE_16, pow42Scaled16 % BASE_16);
        QDec128 qdecCen2 = new QDec128(q128Cen2._1$mcJ$sp(), q128Cen2._2$mcJ$sp(), SCALE_16);
        long q128Cen2Cents = qdecCen2.projectCentsRound2HalfEven();

        BigDecimal bdCen2Full = probe.bdPrincipal10M.multiply(probe.bdRateFactor.pow(42, MC128), MC128);
        BigDecimal bdCen2Rounded = bdCen2Full.setScale(2, RoundingMode.HALF_EVEN);

        System.out.printf("%n  [Cenário 2] 10.000.000,00 * pow(1+i, 42):%n");
        System.out.printf("    -> Q128 (16 casas)       : %s%n", qdecCen2.toString());
        System.out.printf("    -> BigDecimal (DECIMAL128): %s%n", bdCen2Full.setScale(16, RoundingMode.HALF_EVEN).toPlainString());
        System.out.printf("    -> Q128 (ABNT R_2)       : %s%n", Q128RateEngine.formatCentsAsBrl(q128Cen2Cents));
        System.out.printf("    -> BigDecimal (ABNT R_2) : R$ %s%n", bdCen2Rounded.toPlainString());

        // Auditoria Cenário 3: exp(42.0) = e^42
        Tuple2<Object, Object> q128E42 = QuimeraCategoricalDecimal.exp128(42L, 0L);
        QDec128 qdecE42 = new QDec128(q128E42._1$mcJ$sp(), q128E42._2$mcJ$sp(), SCALE_16);
        BigDecimal bdE42 = bigDecimalExpGeneral(probe.bdExpArg42);

        System.out.printf("%n  [Cenário 3] Exponencial Extrema exp(42.0) = e^42 (Limite de 64 bits):%n");
        System.out.printf("    -> Q128 exp128(42, 0)    : %s%n", qdecE42.toString());
        System.out.printf("    -> BigDecimal (DECIMAL128): %s%n", bdE42.setScale(16, RoundingMode.HALF_EVEN).toPlainString());
        System.out.println("==========================================================================================================\n");

        Options opt = new OptionsBuilder()
                .include(Q128Exp42JmhBenchmark.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .build();

        new Runner(opt).run();
    }
}
