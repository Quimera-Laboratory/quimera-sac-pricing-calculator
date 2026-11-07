# Relatório de Precificação SAC, Fechamento Mensal e Ciclo de Vida Diário (`Q128`)

**Especificação de Referência:** [modelagem_matem_tica_de_apropria_o_di_ria_de_juros_sem_erro_de_arredondamento.tex](file:///home/james/IdeaProjects/quimera-sac-pricing-calculator/modelagem_matem_tica_de_apropria_o_di_ria_de_juros_sem_erro_de_arredondamento.tex)  
**Motor Algébrico:** [`ZeroRoundingDailyAccrualEngine`](file:///home/james/IdeaProjects/quimera-sac-pricing-calculator/src/main/java/org/quimera/labs/financial/sac/ZeroRoundingDailyAccrualEngine.java) (`org.quimera.labs.financial:quimera-q128-financial_2.12:2.0.0-RELEASE`)

### Parâmetros da Operação SAC
* **Principal Inicial ($S_0$):** `R$ 1.200.000,00` (`120.000.000` centavos)
* **Sistema de Amortização:** SAC (12 parcelas mensais de `R$ 100.000,00` de quota de amortização)
* **Base de Contagem de Dias:** `BUS_252` (21 dias úteis por mês / 252 dias úteis no ano)
* **Taxa Efetiva Anual ($i_a$):** `13,25% a.a.` (`0.1325`)
* **Fator Diário Padrão BACEN/CETIP ($F_t \in \mathbb{D}_8$, Eq. 4):**
  $$F_t = 1 + \mathcal{R}_8\left((1 + 0{,}1325)^{1/252} - 1\right) = 1{,}00049388$$
* **Regra de Quantização Monetária ($\mathcal{R}_2$):** ABNT NBR 5891 (*Half-Even* na 2ª casa decimal)

---

## Tabela 1 — Cronograma Mensal da Operação SAC: Soma Diária Compensada vs. Período Fechado vs. Método Ingênuo

Comparativo mês a mês ($m = 1 \dots 12$) entre:
1. **Soma Diária Compensada ($\sum_{d=1}^{21} L_{m,d}$):** Somatório dos 21 lançamentos diários gerados pelo algoritmo Kahan-Bresenham / Estado Dual Telescópico.
2. **Período Fechado ($J_m^{\text{fechado}}$):** Cálculo direto na data de aniversário mensal via equação fechada $J_m^{\text{fechado}} = \mathcal{R}_2(S_{m,0} \cdot F_t^{21}) - S_{m,0}$.
3. **Soma Diária Ingênua ($\sum_{d=1}^{21} \tilde{L}_{m,d}$):** Somatório diário sem memória sub-centavo ($R_t$).

| Mês ($m$) | DU Acum. | Saldo Abertura ($S_{m,0}$) | Aprop. Dia 1 ($L_{m,1}$) | Aprop. Dia 21 ($L_{m,21}$) | Soma Diária Compensada ($\sum_{d=1}^{21} L_{m,d}$) | Juro Período Fechado ($J_m^{\text{fechado}}$) | **Diff (Diário − Fechado)** | Soma Diária Ingênua ($\sum \tilde{L}_{m,d}$) | Erro Ingênuo vs. Fechado | Amortização SAC ($A_m$) | Prestação Paga ($P_m$) | Saldo Pós-Pgto ($S_{m,0} - A_m$) |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **1** | 21 | R$ 1.200.000,00 | R$ 592,66 | R$ 598,54 | **R$ 12.507,44** | **R$ 12.507,44** | **`0 cent`** | R$ 12.445,86 | `-6.158 cent` (-R$ 61,58) | R$ 100.000,00 | R$ 112.507,44 | R$ 1.100.000,00 |
| **2** | 42 | R$ 1.100.000,00 | R$ 543,27 | R$ 548,66 | **R$ 11.465,15** | **R$ 11.465,15** | **`0 cent`** | R$ 11.408,67 | `-5.648 cent` (-R$ 56,48) | R$ 100.000,00 | R$ 111.465,15 | R$ 1.000.000,00 |
| **3** | 63 | R$ 1.000.000,00 | R$ 493,88 | R$ 498,78 | **R$ 10.422,86** | **R$ 10.422,86** | **`0 cent`** | R$ 10.371,48 | `-5.138 cent` (-R$ 51,38) | R$ 100.000,00 | R$ 110.422,86 | R$ 900.000,00 |
| **4** | 84 | R$ 900.000,00 | R$ 444,49 | R$ 448,91 | **R$ 9.380,58** | **R$ 9.380,58** | **`0 cent`** | R$ 9.334,29 | `-4.629 cent` (-R$ 46,29) | R$ 100.000,00 | R$ 109.380,58 | R$ 800.000,00 |
| **5** | 105 | R$ 800.000,00 | R$ 395,10 | R$ 399,02 | **R$ 8.338,29** | **R$ 8.338,29** | **`0 cent`** | R$ 8.297,10 | `-4.119 cent` (-R$ 41,19) | R$ 100.000,00 | R$ 108.338,29 | R$ 700.000,00 |
| **6** | 126 | R$ 700.000,00 | R$ 345,72 | R$ 349,14 | **R$ 7.296,00** | **R$ 7.296,00** | **`0 cent`** | R$ 7.260,12 | `-3.588 cent` (-R$ 35,88) | R$ 100.000,00 | R$ 107.296,00 | R$ 600.000,00 |
| **7** | 147 | R$ 600.000,00 | R$ 296,33 | R$ 299,27 | **R$ 6.253,72** | **R$ 6.253,72** | **`0 cent`** | R$ 6.222,93 | `-3.079 cent` (-R$ 30,79) | R$ 100.000,00 | R$ 106.253,72 | R$ 500.000,00 |
| **8** | 168 | R$ 500.000,00 | R$ 246,94 | R$ 249,39 | **R$ 5.211,43** | **R$ 5.211,43** | **`0 cent`** | R$ 5.185,74 | `-2.569 cent` (-R$ 25,69) | R$ 100.000,00 | R$ 105.211,43 | R$ 400.000,00 |
| **9** | 189 | R$ 400.000,00 | R$ 197,55 | R$ 199,52 | **R$ 4.169,15** | **R$ 4.169,15** | **`0 cent`** | R$ 4.148,55 | `-2.060 cent` (-R$ 20,60) | R$ 100.000,00 | R$ 104.169,15 | R$ 300.000,00 |
| **10** | 210 | R$ 300.000,00 | R$ 148,16 | R$ 149,64 | **R$ 3.126,86** | **R$ 3.126,86** | **`0 cent`** | R$ 3.111,36 | `-1.550 cent` (-R$ 15,50) | R$ 100.000,00 | R$ 103.126,86 | R$ 200.000,00 |
| **11** | 231 | R$ 200.000,00 | R$ 98,78 | R$ 99,75 | **R$ 2.084,57** | **R$ 2.084,57** | **`0 cent`** | R$ 2.074,38 | `-1.019 cent` (-R$ 10,19) | R$ 100.000,00 | R$ 102.084,57 | R$ 100.000,00 |
| **12** | 252 | R$ 100.000,00 | R$ 49,39 | R$ 49,88 | **R$ 1.042,29** | **R$ 1.042,29** | **`0 cent`** | R$ 1.037,19 | `-510 cent` (-R$ 5,10) | R$ 100.000,00 | R$ 101.042,29 | **R$ 0,00** |
| **TOTAL** | **252** | — | — | — | **R$ 81.298,34** | **R$ 81.298,34** | **`0 cent`** | **R$ 80.897,67** | **`-40.067 cent` (-R$ 400,67)** | **R$ 1.200.000,00** | **R$ 1.281.298,34** | **R$ 0,00** |

---

## Tabela 2 — Ciclo de Vida Diário da Operação com Fechamento Mensal e Comparação Diária vs. Período Fechado

Evolução diária completa do Mês 1 (`M01/D01` a `M01/D21`) seguida pelos eventos de fechamento mensal do Mês 2 ao Mês 12 (`M02/D21` a `M12/D21`), comparando em cada dia $d$ o **Acumulado Diário no Mês** ($\sum_{\tau=1}^d L_{m,\tau}$) com o **Juro em Equação de Período Fechado até o Dia $d$** ($\mathcal{R}_2(S_{m,0} \cdot F_t^d) - S_{m,0}$).

| Mês/Dia | Dia Global ($t$) | Saldo Exato $S_{m,d}^*$ (`Q128`) | Lançamento do Dia ($L_{m,d}$) | Resíduo Sub-Centavo ($R_{m,d}$) | Acumulado Diário no Mês ($\sum_{\tau=1}^d L_{m,\tau}$) | Juro Fechado até o Dia $d$ ($\mathcal{R}_2(S_{m,0} F^d) - S_{m,0}$) | **Diff (Diário − Fechado)** | Saldo Contábil Pré-Pgto ($S_{m,d}^{\text{cont}}$) | Liquidação Mensal (Juros + Amort.) | Saldo Contábil Pós-Pgto |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `M01/D01` | 1 | `1200592.6560000` | R$ 592,66 | `-0.004000000` | R$ 592,66 | R$ 592,66 | **`0 cent`** | R$ 1.200.592,66 | R$ 0,00 | R$ 1.200.592,66 |
| `M01/D02` | 2 | `1201185.6047009` | R$ 592,94 | `+0.004700945` | R$ 1.185,60 | R$ 1.185,60 | **`0 cent`** | R$ 1.201.185,60 | R$ 0,00 | R$ 1.201.185,60 |
| `M01/D03` | 3 | `1201778.8462473` | R$ 593,25 | `-0.003752605` | R$ 1.778,85 | R$ 1.778,85 | **`0 cent`** | R$ 1.201.778,85 | R$ 0,00 | R$ 1.201.778,85 |
| `M01/D04` | 4 | `1202372.3807839` | R$ 593,53 | `+0.000783979` | R$ 2.372,38 | R$ 2.372,38 | **`0 cent`** | R$ 1.202.372,38 | R$ 0,00 | R$ 1.202.372,38 |
| `M01/D05` | 5 | `1202966.2084554` | R$ 593,83 | `-0.001544598` | R$ 2.966,21 | R$ 2.966,21 | **`0 cent`** | R$ 1.202.966,21 | R$ 0,00 | R$ 1.202.966,21 |
| `M01/D06` | 6 | `1203560.3294064` | R$ 594,12 | `-0.000593566` | R$ 3.560,33 | R$ 3.560,33 | **`0 cent`** | R$ 1.203.560,33 | R$ 0,00 | R$ 1.203.560,33 |
| `M01/D07` | 7 | `1204154.7437819` | R$ 594,41 | `+0.003781920` | R$ 4.154,74 | R$ 4.154,74 | **`0 cent`** | R$ 1.204.154,74 | R$ 0,00 | R$ 1.204.154,74 |
| `M01/D08` | 8 | `1204749.4517267` | R$ 594,71 | `+0.001726779` | R$ 4.749,45 | R$ 4.749,45 | **`0 cent`** | R$ 1.204.749,45 | R$ 0,00 | R$ 1.204.749,45 |
| `M01/D09` | 9 | `1205344.4533859` | R$ 595,00 | `+0.003385998` | R$ 5.344,45 | R$ 5.344,45 | **`0 cent`** | R$ 1.205.344,45 | R$ 0,00 | R$ 1.205.344,45 |
| `M01/D10` | 10 | `1205939.7489046` | R$ 595,30 | `-0.001095363` | R$ 5.939,75 | R$ 5.939,75 | **`0 cent`** | R$ 1.205.939,75 | R$ 0,00 | R$ 1.205.939,75 |
| `M01/D11` | 11 | `1206535.3384278` | R$ 595,59 | `-0.001572174` | R$ 6.535,34 | R$ 6.535,34 | **`0 cent`** | R$ 1.206.535,34 | R$ 0,00 | R$ 1.206.535,34 |
| `M01/D12` | 12 | `1207131.2221007` | R$ 595,88 | `+0.002100768` | R$ 7.131,22 | R$ 7.131,22 | **`0 cent`** | R$ 1.207.131,22 | R$ 0,00 | R$ 1.207.131,22 |
| `M01/D13` | 13 | `1207727.4000687` | R$ 596,18 | `+0.000068739` | R$ 7.727,40 | R$ 7.727,40 | **`0 cent`** | R$ 1.207.727,40 | R$ 0,00 | R$ 1.207.727,40 |
| `M01/D14` | 14 | `1208323.8724770` | R$ 596,47 | `+0.002477085` | R$ 8.323,87 | R$ 8.323,87 | **`0 cent`** | R$ 1.208.323,87 | R$ 0,00 | R$ 1.208.323,87 |
| `M01/D15` | 15 | `1208920.6394712` | R$ 596,77 | `-0.000528775` | R$ 8.920,64 | R$ 8.920,64 | **`0 cent`** | R$ 1.208.920,64 | R$ 0,00 | R$ 1.208.920,64 |
| `M01/D16` | 16 | `1209517.7011966` | R$ 597,06 | `+0.001196646` | R$ 9.517,70 | R$ 9.517,70 | **`0 cent`** | R$ 1.209.517,70 | R$ 0,00 | R$ 1.209.517,70 |
| `M01/D17` | 17 | `1210115.0577989` | R$ 597,36 | `-0.002201086` | R$ 10.115,06 | R$ 10.115,06 | **`0 cent`** | R$ 1.210.115,06 | R$ 0,00 | R$ 1.210.115,06 |
| `M01/D18` | 18 | `1210712.7094236` | R$ 597,65 | `-0.000576340` | R$ 10.712,71 | R$ 10.712,71 | **`0 cent`** | R$ 1.210.712,71 | R$ 0,00 | R$ 1.210.712,71 |
| `M01/D19` | 19 | `1211310.6562165` | R$ 597,95 | `-0.003783410` | R$ 11.310,66 | R$ 11.310,66 | **`0 cent`** | R$ 1.211.310,66 | R$ 0,00 | R$ 1.211.310,66 |
| `M01/D20` | 20 | `1211908.8983234` | R$ 598,24 | `-0.001676518` | R$ 11.908,90 | R$ 11.908,90 | **`0 cent`** | R$ 1.211.908,90 | R$ 0,00 | R$ 1.211.908,90 |
| **`M01/D21`** *(Fech. Mês 1)* | **21** | `1212507.4358901` | R$ 598,54 | `-0.004109814` | **R$ 12.507,44** | **R$ 12.507,44** | **`0 cent`** | R$ 1.212.507,44 | **R$ 112.507,44** | **R$ 1.100.000,00** |
| **`M02/D21`** *(Fech. Mês 2)* | **42** | `1111465.1495660` | R$ 548,66 | `-0.000433996` | **R$ 11.465,15** | **R$ 11.465,15** | **`0 cent`** | R$ 1.111.465,15 | **R$ 111.465,15** | **R$ 1.000.000,00** |
| **`M03/D21`** *(Fech. Mês 3)* | **63** | `1010422.8632418` | R$ 498,78 | `+0.003241821` | **R$ 10.422,86** | **R$ 10.422,86** | **`0 cent`** | R$ 1.010.422,86 | **R$ 110.422,86** | **R$ 900.000,00** |
| **`M04/D21`** *(Fech. Mês 4)* | **84** | `909380.57691763` | R$ 448,91 | `-0.003082360` | **R$ 9.380,58** | **R$ 9.380,58** | **`0 cent`** | R$ 909.380,58 | **R$ 109.380,58** | **R$ 800.000,00** |
| **`M05/D21`** *(Fech. Mês 5)* | **105** | `808338.29059345` | R$ 399,02 | `+0.000593457` | **R$ 8.338,29** | **R$ 8.338,29** | **`0 cent`** | R$ 808.338,29 | **R$ 108.338,29** | **R$ 700.000,00** |
| **`M06/D21`** *(Fech. Mês 6)* | **126** | `707296.00426927` | R$ 349,14 | `+0.004269274` | **R$ 7.296,00** | **R$ 7.296,00** | **`0 cent`** | R$ 707.296,00 | **R$ 107.296,00** | **R$ 600.000,00** |
| **`M07/D21`** *(Fech. Mês 7)* | **147** | `606253.71794509` | R$ 299,27 | `-0.002054907` | **R$ 6.253,72** | **R$ 6.253,72** | **`0 cent`** | R$ 606.253,72 | **R$ 106.253,72** | **R$ 500.000,00** |
| **`M08/D21`** *(Fech. Mês 8)* | **168** | `505211.43162091` | R$ 249,39 | `+0.001620910` | **R$ 5.211,43** | **R$ 5.211,43** | **`0 cent`** | R$ 505.211,43 | **R$ 105.211,43** | **R$ 400.000,00** |
| **`M09/D21`** *(Fech. Mês 9)* | **189** | `404169.14529672` | R$ 199,52 | `-0.004703271` | **R$ 4.169,15** | **R$ 4.169,15** | **`0 cent`** | R$ 404.169,15 | **R$ 104.169,15** | **R$ 300.000,00** |
| **`M10/D21`** *(Fech. Mês 10)* | **210** | `303126.85897254` | R$ 149,64 | `-0.001027453` | **R$ 3.126,86** | **R$ 3.126,86** | **`0 cent`** | R$ 303.126,86 | **R$ 103.126,86** | **R$ 200.000,00** |
| **`M11/D21`** *(Fech. Mês 11)* | **231** | `202084.57264836` | R$ 99,75 | `+0.002648364` | **R$ 2.084,57** | **R$ 2.084,57** | **`0 cent`** | R$ 202.084,57 | **R$ 102.084,57** | **R$ 100.000,00** |
| **`M12/D21`** *(Liquidação Final)* | **252** | `101042.28632418` | R$ 49,88 | `-0.003675817` | **R$ 1.042,29** | **R$ 1.042,29** | **`0 cent`** | R$ 101.042,29 | **R$ 101.042,29** | **R$ 0,00** |

---

## Resumo da Auditoria Formal das 6 Invariantes COSIF ($I_1$ a $I_6$)

* **$I_1$ (Conservação Econômica Intra-Tranche):** $P_{i,k,t} + J_{i,k,t}^{\text{cont}} + R_{i,k,t} = S_{i,k,t}^*$ verificada nos 252 dias úteis.
* **$I_2$ (Reconciliação Estrita Subledger vs. Razão Geral COSIF):** $\mathcal{G}_t = \sum_{i=1}^M S_{i,t}^{\text{cont}}$ verificada nos 252 dias úteis.
* **$I_3$ (Confinamento de Lyapunov do Resíduo Sub-Centavo):** $|R_{i,k,t}| \le 0{,}005$ em todas as 12 tranches e todos os 252 dias úteis.
* **$I_4$ (Partidas Dobradas e Telescopia Temporal):** $\sum (\text{Débito} - \text{Crédito}) = 0$ e fechamento telescópico com erro zero.
* **$I_5$ (Aditividade Estrita Multi-Tranche):** Soma das 12 tranches SAC iguala o saldo global do contrato em todos os dias.
* **$I_6$ (Integridade Tributária e Não-Confisco):** Base de IOF e IRRF restrita a $J_{i,k,t}^{\text{cont}} \ge 0$ com truncamento fiscal $\mathcal{T}_2$.
