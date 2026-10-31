# Decisões do PRD Complementar — Segurança, Acesso e Portaria

Aprovação do responsável em 31/10/2026 (16:22, America/Sao_Paulo): executar o PRD complementar
(`CondPulse_PRD_Complementar_Seguranca_v1.0.md`). Este arquivo registra o que foi adotado e o que continua pendente
(seção 15 do PRD). Nada aqui inventa fato: item sem decisão fica PENDENTE e o código não depende dele.

## Adotado

| Item | Decisão | Origem |
| --- | --- | --- |
| Financeiro condominial (V036, Asaas) | Congelado: não é apagado nem evoluído | PRD §1 item 2 |
| Hierarquia | Plataforma (Master) → Parceiro → Condomínio → Unidade | PRD §3 |
| Quem paga | Parceiro; condomínio sem parceiro é operado pela plataforma no papel de parceiro | PRD §2 |
| Equipamento do piloto | Control iD no Condomínio Teste 1 (escolha anterior do responsável) | Sessão de 31/10/2026 |
| Numeração | Migrations do PRD começam no próximo número livre da base (V048 já foi usada pelo contrato do gateway) | Regra do Flyway sem lacuna |
| Ordem | Primeiro CPX-001 a CPX-011 (hierarquia + Log de Segurança); depois os demais | PRD §16 item 2 |

## Pendente (responsável)

| Item | Efeito enquanto pendente |
| --- | --- |
| Parceiro piloto | Hierarquia é construída e testada com dados sintéticos; nenhum parceiro real é cadastrado |
| Tecnologia do interfone (CPX-039) | Ticket não começa |
| Quem cria restrição geral e base legal (E5) | Lista de bloqueio só começa após a decisão; dupla aprovação já prevista |
| Retenção do Log de Segurança | Nenhuma rotina de expurgo é criada; o log só acrescenta |
| Login real com MFA e aceite dos tickets do PRD v1.0 (portão da Onda 0) | Fatias novas seguem atrás de flag desligada na homologação até o portão |
