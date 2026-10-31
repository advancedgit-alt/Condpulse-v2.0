# ADR-0011 - Hierarquia de Parceiro e Módulo Segurança, Acesso e Portaria

Estado: aceita pelo responsável em 31/10/2026 (chat, 16:22 America/Sao_Paulo, ao receber a análise do PRD
complementar: "Então faça tudo. Que enviei aí"). Referências: PRD CondPulse v1.0 (capítulo 8), PRD Complementar
Segurança v1.0 (`docs/prd/CondPulse_PRD_Complementar_Seguranca_v1.0.md`), ADR-0007, ADR-0008,
`docs/prd/DECISOES_COMPLEMENTAR.md`.

## Contexto

O PRD v1.0 (capítulo 8) prevê dois níveis de acesso: plataforma e vínculo individual por condomínio. O PRD
complementar posiciona o CondPulse como sistema de segurança, acesso e portaria vendido a empresas parceiras
(segurança, portaria remota e presencial, integradoras), que operam carteiras de condomínios, e retira do produto
as funções de síndico e o financeiro condominial.

## Decisão

1. **Substitui parte do PRD v1.0, capítulo 8:** passa a existir o nível **Parceiro** entre a plataforma e o
   condomínio (Plataforma → Parceiro → Condomínio → Unidade). A equipe do parceiro acessa um condomínio somente
   com (a) contrato ativo parceiro × condomínio, (b) papel ativo no parceiro e (c) escala vigente para operador e
   supervisor (R3.1–R3.5 do PRD complementar). Gestor do parceiro e gestor líder de segurança acessam os
   condomínios com contrato ativo, sem escala, e toda leitura de dado pessoal é registrada no Log de Segurança.
2. O isolamento por condomínio (`tenant_id` + RLS) continua sendo a barreira; o vínculo de parceiro é uma segunda
   via de acesso, verificada no banco, nunca só no frontend. Os papéis e regras do v1.0 que não conflitam com o
   item 1 continuam valendo.
3. **Log de Segurança** único, só acrescenta, encadeado por hash por condomínio, gravado na mesma transação do
   evento de domínio (seção 5 do PRD complementar).
4. **Escopo:** funções de síndico ficam no App Advanced; o financeiro condominial da ADR-0008 fica congelado
   (código preservado, sem evolução) até decisão do Módulo Administradora. O financeiro da assinatura (ADR-0007)
   continua e passa a cobrar o parceiro.
5. Em caso de conflito sobre autorização, prevalecem esta ADR e o PRD complementar; demais pontos seguem o PRD v1.0.

## Consequências

- Novas migrations (próximo número livre), capacidades de parceiro e de segurança, painéis por função e rota
  `/p/{parceiro}`.
- Pendências do responsável (parceiro piloto, interfone, base legal da lista de bloqueio, retenção do log)
  continuam em `docs/prd/DECISOES_COMPLEMENTAR.md`; nenhuma rotina depende delas até a decisão.
- As fatias novas seguem atrás de flag desligada na homologação até o portão da Onda 0.
