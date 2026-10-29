# Runbooks operacionais

## RB01 — Leitor offline / revogação urgente

Responsáveis: operador local e integrador; suporte CondPulse acompanha o estado. Confirmar qual leitor está afetado e há quanto tempo. Não declarar credencial removida se não há confirmação. Aplicar bloqueio/procedimento local previsto pelo integrador. Não usar comandos improvisados ou desabilitar saídas de emergência. Registrar incidente, pessoas autorizadas e horário. Após retorno, reconciliar versão desejada, confirmar revogação por destino e só então encerrar. Preservar evidência sem foto biométrica em ticket genérico.

## RB02 — Duplicação de reserva ou inconsistência financeira

Pausar novas confirmações somente da área afetada, sem derrubar todo o produto. Consultar transações, versões e idempotency keys; não editar estado diretamente no banco. Preservar direitos dos moradores conforme decisão da gestão. Corrigir por comando auditado/migration aprovada e criar regressão concorrente. Taxa lançada não é pagamento recebido.

## RB03 — Suspeita de vazamento entre unidades

Severidade máxima. Suspender endpoint/feature afetado, revogar sessões conforme risco, preservar logs sem expandir exposição e acionar responsáveis técnico e de proteção de dados. Determinar alcance por tenant/pessoa/objeto. Corrigir e testar matriz completa antes de religar. Comunicações e notificações a autoridades/titulares dependem da avaliação legal e das regras aplicáveis; não ocultar incidente nem prometer que só a rotação resolve tudo.

## RB04 — Restore isolado

Registrar objetivo, backup e instante. Criar destino isolado; desativar worker, e-mail, push e acesso físico. Restaurar banco, roles/configuração conforme provedor e arquivos por inventário. Comparar contagens, amostras, constraints e hashes. Reconciliar revogações ocorridas depois do ponto de restauração usando registro seguro de incidentes/comandos. Medir tempos e perdas. Só habilitar efeitos após aceite conjunto de operação e integrador. Um restore de teste nunca envia comandos ao condomínio.

## RB05 — Publicação e rollback de aplicação

Verificar backup recente/restaurável, migrations expand-contract e feature flags. Publicar artefato por digest, executar readiness e smoke. Diante de regressão, retornar ao digest compatível com o schema atual e desabilitar feature problemática. Não reverter banco com dados novos sem plano específico. Verificar trabalho do worker e ids de comandos. Documentar commit, motivo, métricas e aceite.

## RB06 — Cutover de condomínio

Antes: obter exportações, responsáveis e inventário de hardware; reconciliar dados. Congelar gravação no sistema anterior por processo no horário acordado. Importar delta e conferir reservas/encomendas abertas. Definir fonte oficial; manter acesso de consulta ao legado conforme contrato e finalidade. Treinar cada turno. Não cancelar fornecedor físico até homologação. Depois: acompanhar erros, ativação e suporte; encerrar legado somente com termo e plano de retenção/exportação.

## RB07 — Notificação não recebida

Consultar instalação, permissão, token, outbox, tentativa e receipt. Identificar etapa real sem afirmar leitura. Invalidar tokens reconhecidamente inválidos. Morador pode consultar caixa do app; portaria segue canal alternativo operacional para chegada urgente. Não desbloquear acesso automaticamente porque push falhou. Nunca habilitar disparos WhatsApp sem decisão comercial e de privacidade própria.

## RB08 — Troca de responsável da unidade / desligamento

Validar documento/autorização do condomínio, vigência e pendências. Encerrar vínculo anterior, revogar sessões/convites aplicáveis e enviar revogação de credenciais. Preservar registros necessários de forma restrita. Aprovar novo vínculo sem herdar automaticamente histórico privado de antigos moradores. Confirmar aplicação física ou escalar pendências antes de anunciar conclusão.
