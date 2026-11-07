# Quimera SAC Pricing & Zero-Rounding Daily Appropriation Calculator (`Java 17` + `Q128`)

High-precision **SAC (Constant Amortization System) Pricing, Zero-Rounding Daily Interest Appropriation (BACEN / CETIP / COSIF & ABNT NBR 5891), Early Settlement, and Mark-to-Market (MtM) Calculator** in **Java 17**, built on top of the 128-bit categorical fixed-point engine **`quimera-q128-financial`** (`org.quimera.labs.financial:quimera-q128-financial_2.12:2.0.0-RELEASE`).

---

## 1. Overview & Supported Day-Count Conventions

The engine computes all financial trajectories in the exact 128-bit arithmetic fiber $\mathbb{D}_{16}$ ($\text{scale } 10^{-16}$) with regulatory projection operators for BACEN/CETIP daily factors in $\mathbb{D}_8$ ($\mathcal{R}_8$, 8 decimal places) and accounting currency in $\mathbb{D}_2$ ($\mathcal{R}_2$, ABNT NBR 5891 Half-Even in cents), supporting any day-count basis:

- **`BUS_252` (252 Business Days)**: B3 / BACEN / CETIP / ANBIMA standard ($\Delta t = \text{BD} / 252$).
- **`ACT_360` (360 Commercial / Calendar Days)**: $\text{Actual}/360$ and $30/360$ standard ($\Delta t = \text{CD} / 360$).
- **`ACT_365` (365 Fixed Calendar Days)**: $\text{Actual}/365$ Fixed standard ($\Delta t = \text{CD} / 365$).

### Project Architecture

| Class / File | Responsibility |
| :--- | :--- |
| [`Q128RateEngine.java`](src/main/java/org/quimera/labs/financial/sac/Q128RateEngine.java) | Java wrapper for `QuimeraCategoricalDecimal` and `QDec128`, exponential/logarithmic operations (`expFactor64`, `log1pFactor64`, `powFactor64`), and equivalent rate conversion across `252`, `360`, and `365` bases. |
| [`ZeroRoundingDailyAccrualEngine.java`](src/main/java/org/quimera/labs/financial/sac/ZeroRoundingDailyAccrualEngine.java) | Formal implementation of the mathematical specification (Solution 1: Dual-State Telescopic Architecture; Solution 2: Kahan-Bresenham Sub-Cent Residue Accumulation; Partial/Full Amortization; COSIF Subledger vs. General Ledger Reconciliation; and all 6 Formal Invariants $I_1 \dots I_6$). |
| [`SacPricingCalculator.java`](src/main/java/org/quimera/labs/financial/sac/SacPricingCalculator.java) | SAC schedule generation engine, pro-rata die daily appropriation, Mark-to-Market (MtM) valuation, Macaulay/Modified Duration, and early payoff calculation. |
| [`BigDecimalSacReferenceEngine.java`](src/main/java/org/quimera/labs/financial/sac/BigDecimalSacReferenceEngine.java) | Reference implementation in `java.math.BigDecimal` (`MathContext.DECIMAL128`, 34 IEEE 754R digits) and worst-case $N = 420$ months benchmark harness. |
| [`Q128Exp42JmhBenchmark.java`](src/main/java/org/quimera/labs/financial/sac/benchmark/Q128Exp42JmhBenchmark.java) | Official **OpenJDK JMH 1.37 + GCProfiler** benchmark comparing `exp(10000000, 42)` between `Q128` and `BigDecimal`. |
| [`RELATORIO_CRONOGRAMA_E_CICLO_VIDA_DIARIO_SAC.md`](RELATORIO_CRONOGRAMA_E_CICLO_VIDA_DIARIO_SAC.md) | Complete comparison tables of the Closed-Form Monthly Schedule vs. Compensated Daily Sum and the full 252-day Operation Daily Lifecycle. |
| [`modelagem_matem_tica_de_apropria_o_di_ria_de_juros_sem_erro_de_arredondamento.tex`](modelagem_matem_tica_de_apropria_o_di_ria_de_juros_sem_erro_de_arredondamento.tex) | Formal LaTeX mathematical specification of the theorems, algorithms, and accounting invariants verified by the engine. |

---

## 2. Unit Test Suite & Formal Invariant Verification (`18/18 PASS`)

All **18 automated JUnit 5 tests** ([`ZeroRoundingDailyAccrualEngineTest.java`](src/test/java/org/quimera/labs/financial/sac/ZeroRoundingDailyAccrualEngineTest.java) and [`SacPricingCalculatorTest.java`](src/test/java/org/quimera/labs/financial/sac/SacPricingCalculatorTest.java)) pass with **0 failures**:

| Test Suite | Test Cases | Status | Validated Scope |
| :--- | :---: | :---: | :--- |
| `ZeroRoundingDailyAccrualEngineTest` | `8` | **PASS (0 failures)** | Operators $\mathcal{R}_8$, $\mathcal{R}_2$ (ABNT NBR 5891), $\mathcal{T}_2$ (RFB tax truncation), exact reproduction of Tables 1 and 2 of the LaTeX specification, Day-10 partial amortization preserving $R_{10}^{\text{post}} = R_{10}$, monthly closing vs. daily lifecycle ($252$ days), multi-tranche COSIF reconciliation, and worst-case $N = 420$ months stress test. |
| `SacPricingCalculatorTest` | `10` | **PASS (0 failures)** | SAC schedule across `252`, `360`, and `365` bases, cross-basis rate equivalence, Mark-to-Market (par, premium, and discount), early settlement, Zero-Heap columnar vector (`QDec128ColumnarVector`), and parity against `BigDecimal`. |

### Verification of the 6 Formal COSIF/BACEN Invariants ($I_1$ to $I_6$)

- **$I_1$ (Sub-Cent Conservation)**: $P_t + J_t^{\text{cont}} + R_t = S_t^*$ holds exactly down to $10^{-16}$ on **100% of days**.
- **$I_2$ (Ledger Telescoping Identity)**: $P_0 + \sum_{k=1}^t (L_k + \Delta E_k) = S_t^{\text{cont}} = \mathcal{R}_2(S_t^*)$ with **`0.00 cent` error**.
- **$I_3$ (Lyapunov Residue Confinement)**: $|R_t| \le 0.005$ ($\le \frac{1}{2}$ cent) on **100% of days**.
- **$I_4$ (COSIF Subledger vs. General Ledger Reconciliation)**: $\sum_k S_{k,t}^{\text{cont}} = \mathcal{R}_2(\sum_k S_{k,t}^*) - \mathcal{R}_2(\sum_k R_{k,t})$ with **zero unexplained divergence**.
- **$I_5$ (Dual-State & Kahan-Bresenham Equivalence)**: $L_t^{\text{Dual}} \equiv L_t^{\text{Kahan}}$ on **100% of days**.
- **$I_6$ (Full Settlement Closure)**: When $\theta_t = 1$, $P_t = 0$, $J_t^{\text{cont}} = 0$, and $R_t = 0$.

---

## 3. Formal LaTeX Specification Benchmark ($P_0 = \text{R\$ } 100,000.00$, $11.25\%$ p.a., 21 BD)

Exact reproduction of Section 6 of [`modelagem_matem_tica_de_apropria_o_di_ria_de_juros_sem_erro_de_arredondamento.tex`](modelagem_matem_tica_de_apropria_o_di_ria_de_juros_sem_erro_de_arredondamento.tex):

- **BACEN/CETIP Daily Factor ($\mathbb{D}_8$)**: $F_t = \mathcal{R}_8((1.1125)^{1/252}) = \mathbf{1.00042314}$
- **Exact Balance on Day 21 ($S_{21}^* \in \mathbb{D}_{16}$)**: $\text{R\$ } 100,892.3696662533587788$
- **Closed-Form Period Interest ($\mathcal{R}_2(S_{21}^*) - P_0$)**: **`R$ 892.37`**
- **Sum of 21 Daily Ledger Entries (`Q128` Dual-State / Kahan-Bresenham)**: **`R$ 892.37`** (**Error = `R$ 0.00`**)
- **Sum via Naive Daily Rounding**: `R$ 892.27` (Cumulative error of **`-R$ 0.10`** / `-10 cents`)
- **Intermediate Partial Amortization Event on Day 10 ($\Delta E_{10} = -\text{R\$ } 30,000.00$)**:
  - Pre-event sub-cent residue $R_{10} = -0.00478921\dots \equiv R_{10}^{\text{post}}$ (preserved without sub-cent loss).
  - Total monthly interest with partial amortization: **`R$ 748.82`** (Closed-Form Tranche Product = Compensated Daily Sum, vs. `R$ 748.79` in the naive method).

---

## 4. Monthly Closed-Form vs. Daily Lifecycle Comparison (SAC Contract $\text{R\$ } 1,200,000.00$, $13.25\%$ p.a., 12 Months)

Full tables are available in [`RELATORIO_CRONOGRAMA_E_CICLO_VIDA_DIARIO_SAC.md`](RELATORIO_CRONOGRAMA_E_CICLO_VIDA_DIARIO_SAC.md).

| Consolidated Metric (252 Business Days / 12 SAC Installments) | Closed-Form Monthly Period | `Q128` Compensated Daily Sum (Kahan-Bresenham) | Naive Daily Rounding Method |
| :--- | ---: | ---: | ---: |
| **Total Amortized Principal** | `R$ 1,200,000.00` | `R$ 1,200,000.00` | `R$ 1,200,000.00` |
| **Total Accrued Interest (Year)** | **`R$ 81,298.34`** | **`R$ 81,298.34`** | `R$ 80,897.67` |
| **Total Settled Installments** | **`R$ 1,281,298.34`** | **`R$ 1,281,298.34`** | `R$ 1,280,897.67` |
| **Cumulative Divergence (Daily − Closed Period)** | — | **`R$ 0.00` (`0 cents`)** | **`-R$ 400.67` (`-40,067 cents`)** |

---

## 5. Worst-Case Stress Test: Principal $\text{R\$ } 1,000,000.00$, $N = 420$ Months (35 Years)

Executed in [`BigDecimalSacReferenceEngine.runWorstCaseExp420Comparison`](src/main/java/org/quimera/labs/financial/sac/BigDecimalSacReferenceEngine.java), combining all pathological fixed/floating-point edge cases:
1. **Infinite Repeating Decimal Amortization Quota**: $A = \frac{1,000,000.00}{420} = \text{R\$ } 2,380.95238095238095\dots$ (compensated via Kahan-Bresenham so that Month 420 closes with exactly $\text{R\$ } 0.00$ balance and a final quota of $\text{R\$ } 2,380.95$).
2. **35-Year Horizon ($N = 420$ months)**: **$8,820$ business days** (`BUS_252`) and **$12,600$ calendar days** (`ACT_360` and `ACT_365`).
3. **Prime-Fractional Annual Interest Rate**: $i_a = 14.7931\%$ p.a. (MtM discount rate $13.6789\%$ p.a.).

| Basis ($N = 420$ Months / 35 Years) | Total Days | Daily Factor $F_t \in \mathbb{D}_8$ | Total Interest `Q128` | Total Interest `BigDecimal (DECIMAL128)` | Total Diff (`Q128 - BD`) | Max Daily Diff | Avoided Naive Rounding Error | `Q128` Time | `BigDecimal` Time | `Q128` Speedup |
| :--- | :---: | :---: | ---: | ---: | :---: | :---: | ---: | ---: | ---: | :---: |
| **`BUS_252` (252 BD/yr)** | `8,820` | `1.00054761` | **`R$ 2,434,013.41`** | **`R$ 2,434,013.41`** | **`0.00 cent`** | **`0 cent`** | `-R$ 13,302.04` | `2.99 ms` | `26.17 ms` | **`8.75x`** |
| **`ACT_360` (360 CD/yr)** | `12,600` | `1.00038330` | **`R$ 2,434,042.01`** | **`R$ 2,434,042.01`** | **`0.00 cent`** | **`0 cent`** | `-R$ 13,501.01` | `4.87 ms` | `19.32 ms` | **`3.96x`** |
| **`ACT_365` (365 CD/yr)** | `12,600` | `1.00037805` | **`R$ 2,400,520.28`** | **`R$ 2,400,520.28`** | **`0.00 cent`** | **`0 cent`** | `-R$ 13,133.18` | `4.83 ms` | `18.62 ms` | **`3.86x`** |

---

## 6. Official OpenJDK JMH 1.37 + `GCProfiler` Benchmark: `exp(10000000, 42)` (`Q128` vs. `BigDecimal`)

Executed via [`Q128Exp42JmhBenchmark.java`](src/main/java/org/quimera/labs/financial/sac/benchmark/Q128Exp42JmhBenchmark.java) (`JMH 1.37`, `@Fork(1)`, `-Xms1G -Xmx1G -XX:+UseG1GC`, `-prof gc`), covering all 3 mathematical and financial interpretations of `exp(10000000, 42)`:

1. **Scenario 1 — Continuous Exponential Compounding on `R$ 10,000,000.42` (`10000000,42`)**:
   $$S = 10,000,000.42 \times \exp(42 \cdot \ln(1 + i))$$
2. **Scenario 2 — Integer Exponentiation `exp(base, 42)` / `pow(factor, 42)` on `R$ 10,000,000.00`**:
   $$S = 10,000,000.00 \times (1 + i)^{42}$$
3. **Scenario 3 — Extreme Direct Exponential $\exp(42.0) = e^{42}$ at the 64-Bit Signed Ceiling**:
   $$e^{42} \approx 1.739274941520501047 \times 10^{18} \quad (\text{near } 2^{63}-1 \approx 9.223 \times 10^{18})$$

### 6.1. Pre-JMH Numerical Parity Audit

| Scenario | Exact `Q128` Value ($\mathbb{D}_{16}$ — 16 decimals) | Exact `BigDecimal` Value (`DECIMAL128`) | ABNT $\mathcal{R}_2$ Currency (`Q128` vs. `BD`) | Divergence |
| :--- | :--- | :--- | :--- | :---: |
| **Scenario 1** (`10,000,000.42 * exp(42 * ln(1+i))`) | `12575684.0451310237119972` | `12575684.0451310896946891` | **`R$ 12,575,684.05`** vs **`R$ 12,575,684.05`** | **`0.00 cent`** |
| **Scenario 2** (`10,000,000.00 * pow(1+i, 42)`) | `12575683.5169523410000000` | `12575683.5169523819826890` | **`R$ 12,575,683.52`** vs **`R$ 12,575,683.52`** | **`0.00 cent`** |
| **Scenario 3** ($\exp(42.0) = e^{42}$) | `1739274941520501047.3946813036112352` | `1739274941520501047.3946813036112490` | **33 identical significant digits** | **$< 1.4 \times 10^{-14}$** |

### 6.2. JMH 1.37 Latency (`ns/op`) & Heap Allocation (`gc.alloc.rate.norm`) Results

| JMH Benchmark | Engine | Latency (`ns/op`) | Error ($\pm 99.9\%$) | Speedup vs. `BigDecimal` | Heap Alloc (`B/op`) | GC Rate (`MB/s`) | Heap Reduction |
| :--- | :--- | ---: | ---: | :---: | ---: | ---: | :---: |
| `cen1_q128Primitive_exp_10000000_42` | **`Q128 Primitive`** | **`1,263.464 ns/op`** | `± 115.817` | **`14.15x faster`** | **`40.007 B/op`** | `30.19 MB/s` | **`811x less memory`** |
| `cen1_qDec128Boxed_exp_10000000_42` | **`QDec128 Boxed`** | **`1,401.973 ns/op`** | `± 571.141` | **`12.76x faster`** | **`224.008 B/op`** | `152.40 MB/s` | **`145x less memory`** |
| `cen1_bigDecimal128_exp_10000000_42` | `BigDecimal (DECIMAL128)` | `17,884.032 ns/op` | `± 392.361` | `1.00x` | `32,456.107 B/op` | `1,730.06 MB/s` | `1.0x` |
| `cen2_q128Primitive_pow42_on_10000000` | **`Q128 Primitive`** | **`520.146 ns/op`** | `± 205.044` | **`4.17x faster`** | **`40.003 B/op`** | `73.35 MB/s` | **`90x less memory`** |
| `cen2_qDec128Boxed_pow42_on_10000000` | **`QDec128 Boxed`** | **`688.247 ns/op`** | `± 321.537` | **`3.15x faster`** | **`224.004 B/op`** | `310.44 MB/s` | **`16x less memory`** |
| `cen2_bigDecimal128_pow42_on_10000000` | `BigDecimal (DECIMAL128)` | `2,167.537 ns/op` | `± 846.207` | `1.00x` | `3,592.013 B/op` | `1,580.57 MB/s` | `1.0x` |
| `cen3_q128Primitive_exp42_extreme` | **`Q128 Primitive`** | **`89.463 ns/op`** | `± 20.635` | **`363.50x faster`** | **`40.001 B/op`** | `426.34 MB/s` | **`1,682x less memory`** |
| `cen3_bigDecimal128_exp42_extreme` | `BigDecimal (DECIMAL128)` | `32,520.217 ns/op` | `± 15,680.386` | `1.00x` | `67,272.195 B/op` | `1,973.16 MB/s` | `1.0x` |

---

## 7. Batch Throughput Benchmark (Full SAC Schedule + Daily Appropriation + MtM)

Measured in [`SacPricingCliApp.java`](src/main/java/org/quimera/labs/financial/sac/SacPricingCliApp.java) (Section 4 of the CLI report):

| Metric | `Q128` Engine (`QDec128` + `QuimeraCategoricalDecimal`) | `java.math.BigDecimal` (`DECIMAL128`) | `Q128` Advantage |
| :--- | ---: | ---: | :---: |
| **Throughput (12m SAC Contracts + 252 Daily Rows/s)** | **`74,736 contracts/s`** | `10,184 contracts/s` | **`7.34x faster`** |
| **Average Latency per Full Contract** | **`13.38 µs / contract`** | `98.19 µs / contract` | **`7.34x lower latency`** |
| **Memory Footprint (Columnar Vector Layout)** | **`16 bytes / element`** (`2x long[]`) | `~80 bytes / element` (Object graph) | **`5.0x more compact`** |

---

## 8. How to Run Tests, CLI Report & JMH Benchmarks

```bash
# 1. Run the full JUnit 5 test suite (18 tests: COSIF invariants, 252/360/365 bases, and N=420 worst case)
mvn test

# 2. Run the comprehensive CLI report (Sections 1 to 10, including the N=420 stress test)
mvn exec:java

# 3. Run the official OpenJDK JMH 1.37 + GCProfiler benchmark (exp(10000000, 42))
CP="target/classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q)"
java -cp "$CP" org.quimera.labs.financial.sac.benchmark.Q128Exp42JmhBenchmark
```
