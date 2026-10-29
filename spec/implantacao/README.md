# Implantação proposta — CondPulse

Este diretório é um runbook e contém exemplos. Não cria contas, não reserva domínio, não publica aplicação e não modifica o servidor Advanced. `compose.example.yaml` depende das imagens que ainda serão construídas.

## Escolha inicial

Supabase gerenciado dedicado: Auth, PostgreSQL e Storage privado. Escolher região São Paulo quando disponível para o projeto; região não resolve sozinha obrigações de privacidade. Configurar SMTP próprio e limites do Auth antes de convidar moradores. Coolify: web estática, API/BFF Java e worker Java, com projetos e segredos exclusivos.

Não hospedar a aplicação no servidor Advanced apenas porque já existe Coolify. Auditar capacidade e isolamento primeiro; base deste plano é capacidade dedicada. Para piloto, 4 vCPU/8 GB é hipótese inicial de dimensionamento da aplicação, não capacidade comprovada nem configuração suficiente para banco self-hosted adicional.

## D0: itens externos necessários

Responsável técnico; conta Supabase; servidor Linux; acesso ao Coolify; domínio e DNS; provedor SMTP; contas Apple/Google; projeto Expo; cofre de segredos; parceiro de controle de acesso e equipamento de bancada; responsáveis nos dois condomínios. Guardar credenciais só no cofre/secret store, nunca nos documentos.

## Sequência de implantação

1. Criar ambientes separados e registrar região, versões, responsáveis e orçamento.
2. Configurar redes, firewall, TLS, DNS e acesso administrativo com MFA/VPN. Banco e console não devem ser expostos sem controles.
3. Configurar Supabase: JWT assimétrico/JWKS, SMTP, regras Auth, bucket privado, acesso à Data API, roles e backup.
4. Rodar migrations em homologação e validar constraints e isolamento como role de aplicação.
5. Construir containers imutáveis com digest e SBOM; assinar/registrar proveniência conforme pipeline disponível.
6. Publicar web/API em homologação e worker com conectores reais desligados; executar smoke e testes.
7. Publicar certificados, CORS allowlist, cookie Secure/HttpOnly/SameSite e limites de request.
8. Integrar cofre, alerta, logs redigidos, monitoramento sintético e procedimento de restauração.
9. Restaurar backup e objetos em ambiente separado. Não habilitar automações durante restore.
10. Importar lote de teste, treinar turnos e aplicar gate de piloto.
11. Promover exatamente os artefatos homologados, com janela e rollback definidos.
12. Liberar tenant por feature flag e medir. Integração física exige aceite próprio por instalação.

## Saúde e atualização

Liveness não deve depender da internet do parceiro. Readiness deve indicar capacidade local de atender o tráfego essencial. Falha do facial degrada somente o módulo, sem derrubar reservas e encomendas. Endpoint de diagnóstico detalhado não é público. Deploy deve drenar conexões SSE e reabrir por consulta do estado real. Flyway roda uma vez em job de release, não em cada réplica.

## Backup / restore

PITR necessário para meta RPO de 15 min deve ser contratado e testado. Backup diário não atende essa meta. Backup do banco não inclui objetos de Storage. Programar cópia versionada/cifrada de objetos e inventário de hashes; testar permissões e restauração separadamente.

RTO de 4 h e RPO de 15 min/banco e 1 h/objetos são metas propostas, não compromissos comerciais já demonstrados. Uma restauração só termina após verificar vínculos, reservas, objetos e estado desejado de revogação, com integrações ainda desabilitadas. Não fazer rollback de banco em produção como atalho para rollback do app.
