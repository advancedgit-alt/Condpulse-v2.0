# Integração facial — portaria e áreas comuns

Pasta reservada para a documentação do fornecedor do reconhecimento facial (liberação de portaria e de
áreas comuns). O responsável vai deixar aqui os arquivos do fornecedor (PDF, coleção de API, manuais).

Estado em 30/10/2026: **BLOQUEADO: documentação do fornecedor — responsável — `docs/integracoes/facial/`**.

Quando a documentação chegar, o trabalho segue `docs/PROMPT_CONTINUIDADE.md` (item 7):

1. Adaptador no padrão dos módulos de acesso já existentes (credenciais, eventos e saúde dos equipamentos —
   ver `docs/ADAPTADOR_ACESSO.md`, `docs/CREDENCIAIS_ACESSO.md`, `docs/REGISTRO_ACESSOS.md`).
2. Webhook assinado do fornecedor, entrada idempotente, fila com repetição e auditoria.
3. Cadastro de face com consentimento explícito (dado biométrico sensível — LGPD art. 11), retenção e exclusão.
4. Roteiro de homologação por equipamento e por cenário (morador, visitante, prestador, área comum,
   negação por horário/vínculo, equipamento fora do ar e retorno), com evidência real.
