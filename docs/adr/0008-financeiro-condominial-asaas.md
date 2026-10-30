# ADR-0008 - Financeiro por condomínio com Asaas

Estado: aceita pelo responsável em 30/10/2026 (chat, 11:38 America/Sao_Paulo: "área financeira por
condomínio — boletos e gestão de cobranças, via Asaas"; resposta "Boletos: Asaas"). Referências: PRD CondPulse
v1.0 (capítulo 4), ADR-0007 ("Financeiro inicial").

## Contexto

O PRD v1.0 deixou cobrança de cotas e emissão de boletos fora do produto inicial, e a ADR-0007 limitou o
financeiro às contas a receber da assinatura SaaS. Em 30/10/2026 o responsável ampliou o escopo: cada
condomínio passa a ter área financeira própria, com emissão e gestão de cobranças pelo Asaas. A chave da API
do **sandbox** do Asaas foi cadastrada pelo responsável no Environment `condpulse-database-operations`
(`CP_ASAAS_API_KEY`).

## Decisão

1. Esta ADR amplia o PRD e a ADR-0007 para incluir o financeiro condominial; o financeiro da assinatura SaaS
   (ADR-0007) continua separado.
2. Provedor: Asaas. Homologação usa somente o sandbox; chave de produção, conta real, taxas e contrato exigem
   OK explícito do responsável.
3. Escopo da primeira entrega: pagador por unidade, cobrança avulsa e recorrente (taxa condominial, reservas,
   multas) por boleto e PIX, consulta de situação, webhook assinado e idempotente, segunda via para o morador,
   inadimplência e relatórios na gestão. Tudo isolado por tenant com RLS e auditoria; a chave nunca chega ao
   navegador.
4. Fora desta ADR: repasse/split, conciliação bancária fora do Asaas, notas fiscais e cobrança judicial.

## Consequências

- Novas migrations, módulo backend, telas de gestão e de morador, e um caminho de segredo até a API na Fly no
  mesmo padrão de `CP_STORAGE_SERVICE_KEY`.
- Produção continua bloqueada até OK explícito do responsável para conta, chave e custos reais do Asaas.
