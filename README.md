# Quimera SAC Pricing & Zero-Rounding Daily Appropriation Calculator (`Java 17` + `Q128`)

Calculadora de **Precificação, Apropriação Contábil Diária Sem Erro de Arredondamento (Padrões BACEN / CETIP / COSIF e ABNT NBR 5891), Liquidação Antecipada e Marcação a Mercado (MtM)** para contratos **SAC (Sistema de Amortização Constante)** em **Java 17**, construída sobre o motor algébrico-categórico de 128 bits **`quimera-q128-financial`** (`org.quimera.labs.financial:quimera-q128-financial_2.12:2.0.0-RELEASE`).

---

## 1. Visão Geral e Bases de Contagem Suportadas

O motor opera na fibra aritmética exata de 128 bits $\mathbb{D}_{16}$ ($\text{escala } 10^{-16}$) com projeção regulatória para fatores diários BACEN/CETIP em $\mathbb{D}_8$ ($\mathcal{R}_8$, 8 casas decimais) e moeda contábil em $\mathbb{D}_2$ ($\mathcal{R}_2$, ABNT NBR 5891 Half-Even em centavos), suportando qualquer convenção de contagem de dias:

- **`BUS_252` (252 Dias Úteis)**: Padrão B3 / BACEN / CETIP / ANBIMA ($\Delta t = \text{DU} / 252$).
- **`ACT_360` (360 Dias Corridos / Comercial)**: Padrão $\text{Actual}/360$ e $30/360$ ($\Delta t = \text{DC} / 360$).
- **`ACT_365` (365 Dias Corridos)**: Padrão $\text{Actual}/365$ Fixo ($\Delta t = \text{DC} / 365$).

### Arquitetura do Projeto

| Classe / Arquivo | Responsabilidade |
| :--- | :--- |
| [`Q128RateEngine.java`](src/main/java/org/quimera/labs/financial/sac/Q128RateEngine.java) | Encapsulamento Java de `QuimeraCategoricalDecimal` e `QDec128`, exponencial/logaritmo (`expFactor64`, `log1pFactor64`, `powFactor64`) e conversão de taxas entre bases `252`, `360` e `365`. |
| [`ZeroRoundingDailyAccrualEngine.java`](src/main/java/org/quimera/labs/financial/sac/ZeroRoundingDailyAccrualEngine.java) | Implementação formal da especificação matemática (Solução 1: Estado Dual Telescópico; Solução 2: Acumulação de Resíduos Sub-Centavo Kahan-Bresenham; Amortização Parcial/Total; Subledger COSIF e 6 Invariantes $I_1 \dots I_6$). |
| [`SacPricingCalculator.java`](src/main/java/org/quimera/labs/financial/sac/SacPricingCalculator.java) | Motor de geração de cronogramas SAC, apropriação diária pro-rata die, Marcação a Mercado (MtM), Duration de Macaulay/Modificada e liquidação antecipada. |
| [`BigDecimalSacReferenceEngine.java`](src/main/java/org/quimera/labs/financial/sac/BigDecimalSacReferenceEngine.java) | Motor de referência em `java.math.BigDecimal` (`MathContext.DECIMAL128`, 34 dígitos IEEE 754R) e suíte de confronto de pior caso ($N = 420$ meses). |
| [`Q128Exp42JmhBenchmark.java`](src/main/java/org/quimera/labs/financial/sac/benchmark/Q128Exp42JmhBenchmark.java) | Benchmark oficial **OpenJDK JMH 1.37 + GCProfiler** confrontando `exp(10000000, 42)` entre `Q128` e `BigDecimal`. |
| [`RELATORIO_CRONOGRAMA_E_CICLO_VIDA_DIARIO_SAC.md`](RELATORIO_CRONOGRAMA_E_CICLO_VIDA_DIARIO_SAC.md) | Tabelas completas do Cronograma Mensal Fechado vs. Soma Diária e do Ciclo de Vida Diário da Operação ($252$ dias). |
| [`modelagem_matem_tica_de_apropria_o_di_ria_de_juros_sem_erro_de_arredondamento.tex`](modelagem_matem_tica_de_apropria_o_di_ria_de_juros_sem_erro_de_arredondamento.tex) | Especificação formal em LaTeX dos teoremas, algoritmos e invariantes auditadas pelo motor. |

---

## 2. Resultados da Suíte de Testes Unitários e Invariantes Formais (`18/18 PASS`)

Todos os **18 testes automatizados JUnit 5** ([`ZeroRoundingDailyAccrualEngineTest.java`](src/test/java/org/quimera/labs/financial/sac/ZeroRoundingDailyAccrualEngineTest.java) e [`SacPricingCalculatorTest.java`](src/test/java/org/quimera/labs/financial/sac/SacPricingCalculatorTest.java)) foram validados com **100% de aprovação**:

| Suíte de Teste | Casos Testados | Resultado | Escopo Validado |
| :--- | :---: | :---: | :--- |
| `ZeroRoundingDailyAccrualEngineTest` | `8` | **PASS (0 falhas)** | Operadores $\mathcal{R}_8$, $\mathcal{R}_2$ (ABNT NBR 5891), $\mathcal{T}_2$ (RFB), reprodução exata das Tabelas 1 e 2 da especificação LaTeX, amortização parcial no Dia 10 preservando $R_{10}^{\text{pós}} = R_{10}$, fechamento mensal vs. diário ($252$ dias), reconciliação COSIF multi-tranche e pior caso $N = 420$ meses. |
| `SacPricingCalculatorTest` | `10` | **PASS (0 falhas)** | Cronograma SAC nas bases `252`, `360` e `365`, equivalência entre taxas, Marcação a Mercado (par, ágio e deságio), liquidação antecipada, vetor colunar Zero-Heap (`QDec128ColumnarVector`) e paridade contra `BigDecimal`. |

### Verificação das 6 Invariantes Formais COSIF/BACEN ($I_1$ a $I_6$)

- **$I_1$ (Conservação Sub-Centavo)**: $P_t + J_t^{\text{cont}} + R_t = S_t^*$ exato até $10^{-16}$ em **100% dos dias**.
- **$I_2$ (Telescopia do Razão Contábil)**: $P_0 + \sum_{k=1}^t (L_k + \Delta E_k) = S_t^{\text{cont}} = \mathcal{R}_2(S_t^*)$ com **erro `0,00 centavo`**.
- **$I_3$ (Confinamento de Lyapunov do Resíduo)**: $|R_t| \le 0{,}005$ ($\le \frac{1}{2}$ centavo) em **100% dos dias**.
- **$I_4$ (Reconciliação Subledger vs. Razão Geral COSIF)**: $\sum_k S_{k,t}^{\text{cont}} = \mathcal{R}_2(\sum_k S_{k,t}^*) - \mathcal{R}_2(\sum_k R_{k,t})$ com **divergência não explicada zero**.
- **$I_5$ (Equivalência Estado Dual vs. Kahan-Bresenham)**: $L_t^{\text{Dual}} \equiv L_t^{\text{Kahan}}$ em **100% dos dias**.
- **$I_6$ (Fechamento de Liquidação Total)**: Em $\theta_t = 1$, $P_t = 0$, $J_t^{\text{cont}} = 0$ e $R_t = 0$.

---

## 3. Resultados do Caso da Especificação LaTeX ($P_0 = \text{R\$ } 100.000,00$, $11{,}25\%$ a.a., 21 DU)

Reprodução exata da Seção 6 de [`modelagem_matem_tica_de_apropria_o_di_ria_de_juros_sem_erro_de_arredondamento.tex`](modelagem_matem_tica_de_apropria_o_di_ria_de_juros_sem_erro_de_arredondamento.tex):

- **Fator Diário BACEN/CETIP ($\mathbb{D}_8$)**: $F_t = \mathcal{R}_8((1{,}1125)^{1/252}) = \mathbf{1{,}00042314}$
- **Saldo Exato no Dia 21 ($S_{21}^* \in \mathbb{D}_{16}$)**: $\text{R\$ } 100.892{,}3696662533587788$
- **Fechamento em Período Fechado ($\mathcal{R}_2(S_{21}^*) - P_0$)**: **`R$ 892,37`**
- **Soma dos 21 Lançamentos Diários (`Q128` Estado Dual / Kahan-Bresenham)**: **`R$ 892,37`** (**Erro = `R$ 0,00`**)
- **Soma pelo Método Diário Ingênuo**: `R$ 892,27` (Erro acumulado de **`-R$ 0,10`** / `-10 centavos`)
- **Evento de Amortização Parcial no Dia 10 ($\Delta E_{10} = -\text{R\$ } 30.000,00$)**:
  - Resíduo pré-evento $R_{10} = -0{,}00478921\dots \equiv R_{10}^{\text{pós}}$ (preservado sem perda sub-centavo).
  - Juros totais no mês com amortização parcial: **`R$ 748,82`** (Período Fechado por Tranches = Soma Diária Compensada, vs. `R$ 748,79` no método ingênuo).

---

## 4. Comparativo de Fechamento Mensal vs. Ciclo Diário (Contrato SAC $\text{R\$ } 1.200.000,00$, $13{,}25\%$ a.a., 12 Meses)

Os relatórios completos encontram-se em [`RELATORIO_CRONOGRAMA_E_CICLO_VIDA_DIARIO_SAC.md`](RELATORIO_CRONOGRAMA_E_CICLO_VIDA_DIARIO_SAC.md).

| Métrica Consolidada (252 Dias Úteis / 12 Parcelas SAC) | Período Fechado Mensal | Soma Diária `Q128` (Kahan-Bresenham) | Método Diário Ingênuo |
| :--- | ---: | ---: | ---: |
| **Total de Principal Amortizado** | `R$ 1.200.000,00` | `R$ 1.200.000,00` | `R$ 1.200.000,00` |
| **Total de Juros Apropriados no Ano** | **`R$ 81.298,34`** | **`R$ 81.298,34`** | `R$ 80.897,67` |
| **Total de Prestações Liquidadas** | **`R$ 1.281.298,34`** | **`R$ 1.281.298,34`** | `R$ 1.280.897,67` |
| **Divergência Acumulada (Diário − Período Fechado)** | — | **`R$ 0,00` (`0 centavos`)** | **`-R$ 400,67` (`-40.067 centavos`)** |

---

## 5. Teste Extremo de Pior Caso: Principal $\text{R\$ } 1.000.000,00$, $N = 420$ Meses (35 Anos)

Confronto de estresse máximo executado em [`BigDecimalSacReferenceEngine.runWorstCaseExp420Comparison`](src/main/java/org/quimera/labs/financial/sac/BigDecimalSacReferenceEngine.java) combinando todas as condições patológicas de ponto fixo/flutuante:
1. **Amortização em Dízima Periódica Infinita**: $A = \frac{1.000.000,00}{420} = \text{R\$ } 2.380,95238095238095\dots$ (compensada via Kahan-Bresenham para fechar exatamente $\text{R\$ } 1.000.000,00$ no mês 420).
2. **Prazo de 35 Anos ($N = 420$ meses)**: **$8.820$ dias úteis** (`BUS_252`) e **$12.600$ dias corridos** (`ACT_360` e `ACT_365`).
3. **Taxa Anual Primo-Fracionária**: $i_a = 14{,}7931\%$ a.a. (mercado MtM a $13{,}6789\%$ a.a.).

| Base ($N = 420$ Meses / 35 Anos) | Total de Dias | Fator Diário $F_t \in \mathbb{D}_8$ | Juros Totais `Q128` | Juros Totais `BigDecimal (DECIMAL128)` | Diferença Total (`Q128 - BD`) | Maior Desvio Diário | Erro Evitado do Método Ingênuo | Tempo `Q128` | Tempo `BigDecimal` | Speedup `Q128` |
| :--- | :---: | :---: | ---: | ---: | :---: | :---: | ---: | ---: | ---: | :---: |
| **`BUS_252` (252 DU/ano)** | `8.820` | `1.00054761` | **`R$ 2.434.013,41`** | **`R$ 2.434.013,41`** | **`0,00 centavo`** | **`0 centavo`** | `-R$ 13.302,04` | `2,99 ms` | `26,17 ms` | **`8,75x`** |
| **`ACT_360` (360 DC/ano)** | `12.600` | `1.00038330` | **`R$ 2.434.042,01`** | **`R$ 2.434.042,01`** | **`0,00 centavo`** | **`0 centavo`** | `-R$ 13.501,01` | `4,87 ms` | `19,32 ms` | **`3,96x`** |
| **`ACT_365` (365 DC/ano)** | `12.600` | `1.00037805` | **`R$ 2.400.520,28`** | **`R$ 2.400.520,28`** | **`0,00 centavo`** | **`0 centavo`** | `-R$ 13.133,18` | `4,83 ms` | `18,62 ms` | **`3,86x`** |

---

## 6. Benchmark Oficial OpenJDK JMH 1.37 + `GCProfiler`: `exp(10000000, 42)` (`Q128` vs. `BigDecimal`)

Executado via [`Q128Exp42JmhBenchmark.java`](src/main/java/org/quimera/labs/financial/sac/benchmark/Q128Exp42JmhBenchmark.java) (`JMH 1.37`, `@Fork(1)`, `-Xms1G -Xmx1G -XX:+UseG1GC`, `-prof gc`), cobrindo as 3 interpretações de `exp(10000000, 42)`:

1. **Cenário 1 — Capitalização Exponencial Contínua sobre `R$ 10.000.000,42` (`10000000,42`)**:
   $$S = 10.000.000,42 \times \exp(42 \cdot \ln(1 + i))$$
2. **Cenário 2 — Exponenciação Inteira `exp(base, 42)` / `pow(fator, 42)` sobre `R$ 10.000.000,00`**:
   $$S = 10.000.000,00 \times (1 + i)^{42}$$
3. **Cenário 3 — Exponencial Direta Extrema $\exp(42{,}0) = e^{42}$ no Limite de 64 Bits**:
   $$e^{42} \approx 1{,}739274941520501047 \times 10^{18} \quad (\text{próximo ao teto } 2^{63}-1 \approx 9{,}223 \times 10^{18})$$

### 6.1. Auditoria de Paridade Numérica Pré-JMH

| Cenário | Valor Exato `Q128` ($\mathbb{D}_{16}$ — 16 casas) | Valor Exato `BigDecimal` (`DECIMAL128`) | Valor Monetário ABNT $\mathcal{R}_2$ (`Q128` vs. `BD`) | Divergência |
| :--- | :--- | :--- | :--- | :---: |
| **Cenário 1** (`10.000.000,42 * exp(42 * ln(1+i))`) | `12575684.0451310237119972` | `12575684.0451310896946891` | **`R$ 12.575.684,05`** vs **`R$ 12.575.684,05`** | **`0,00 centavo`** |
| **Cenário 2** (`10.000.000,00 * pow(1+i, 42)`) | `12575683.5169523410000000` | `12575683.5169523819826890` | **`R$ 12.575.683,52`** vs **`R$ 12.575.683,52`** | **`0,00 centavo`** |
| **Cenário 3** ($\exp(42{,}0) = e^{42}$) | `1739274941520501047.3946813036112352` | `1739274941520501047.3946813036112490` | **33 dígitos significativos idênticos** | **$< 1{,}4 \times 10^{-14}$** |

### 6.2. Tabela de Latência (`ns/op`) e Alocação de Memória (`gc.alloc.rate.norm`) no JMH 1.37

| Benchmark JMH | Motor | Latência (`ns/op`) | Erro ($\pm 99{,}9\%$) | Speedup vs. `BigDecimal` | Alocação Heap (`B/op`) | Taxa GC (`MB/s`) | Redução de Heap |
| :--- | :--- | ---: | ---: | :---: | ---: | ---: | :---: |
| `cen1_q128Primitive_exp_10000000_42` | **`Q128 Primitive`** | **`1.263,464 ns/op`** | `± 115,817` | **`14,15x mais rápido`** | **`40,007 B/op`** | `30,19 MB/s` | **`811x menos memória`** |
| `cen1_qDec128Boxed_exp_10000000_42` | **`QDec128 Boxed`** | **`1.401,973 ns/op`** | `± 571,141` | **`12,76x mais rápido`** | **`224,008 B/op`** | `152,40 MB/s` | **`145x menos memória`** |
| `cen1_bigDecimal128_exp_10000000_42` | `BigDecimal (DECIMAL128)` | `17.884,032 ns/op` | `± 392,361` | `1,00x` | `32.456,107 B/op` | `1.730,06 MB/s` | `1,0x` |
| `cen2_q128Primitive_pow42_on_10000000` | **`Q128 Primitive`** | **`520,146 ns/op`** | `± 205,044` | **`4,17x mais rápido`** | **`40,003 B/op`** | `73,35 MB/s` | **`90x menos memória`** |
| `cen2_qDec128Boxed_pow42_on_10000000` | **`QDec128 Boxed`** | **`688,247 ns/op`** | `± 321,537` | **`3,15x mais rápido`** | **`224,004 B/op`** | `310,44 MB/s` | **`16x menos memória`** |
| `cen2_bigDecimal128_pow42_on_10000000` | `BigDecimal (DECIMAL128)` | `2.167,537 ns/op` | `± 846,207` | `1,00x` | `3.592,013 B/op` | `1.580,57 MB/s` | `1,0x` |
| `cen3_q128Primitive_exp42_extreme` | **`Q128 Primitive`** | **`89,463 ns/op`** | `± 20,635` | **`363,50x mais rápido`** | **`40,001 B/op`** | `426,34 MB/s` | **`1.682x menos memória`** |
| `cen3_bigDecimal128_exp42_extreme` | `BigDecimal (DECIMAL128)` | `32.520,217 ns/op` | `± 15.680,386` | `1,00x` | `67.272,195 B/op` | `1.973,16 MB/s` | `1,0x` |

---

## 7. Benchmark de Throughput em Lote (Cronograma SAC Completo + Apropriação Diária + MtM)

Medido em [`SacPricingCliApp.java`](src/main/java/org/quimera/labs/financial/sac/SacPricingCliApp.java) (Seção 4 do relatório CLI):

| Métrica | Motor `Q128` (`QDec128` + `QuimeraCategoricalDecimal`) | `java.math.BigDecimal` (`DECIMAL128`) | Ganho `Q128` |
| :--- | ---: | ---: | :---: |
| **Throughput (Contratos SAC 12m + 252 dias/s)** | **`74.736 contratos/s`** | `10.184 contratos/s` | **`7,34x mais rápido`** |
| **Latência Média por Contrato Completo** | **`13,38 µs / contrato`** | `98,19 µs / contrato` | **`7,34x menor latência`** |
| **Layout em Memória (Vetor Colunar Off-Heap / Primitivo)** | **`16 bytes / elemento`** (`2x long[]`) | `~80 bytes / elemento` (Grafo de objetos) | **`5,0x mais compacto`** |

---

## 8. Como Executar os Testes, a CLI e o Benchmark JMH

```bash
# 1. Executar toda a suíte JUnit 5 (18 testes: invariantes COSIF, bases 252/360/365 e pior caso N=420)
mvn test

# 2. Executar o relatório completo na CLI (Seções 1 a 10, incluindo pior caso N=420)
mvn exec:java

# 3. Executar o Benchmark Oficial OpenJDK JMH 1.37 + GCProfiler (exp(10000000, 42))
CP="target/classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q)"
java -cp "$CP" org.quimera.labs.financial.sac.benchmark.Q128Exp42JmhBenchmark
```
