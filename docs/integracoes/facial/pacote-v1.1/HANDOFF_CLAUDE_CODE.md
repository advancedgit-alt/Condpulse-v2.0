# Handoff para Claude Code — CondPulse Acesso Facial v1.1

Data: 30/10/2026. Escopo: integração e homologação de acesso facial por condomínio e área, exclusivamente no **CondPulse**.

## Resultado esperado

Evoluir o sistema existente para que o MASTER da plataforma homologue combinações técnicas, o MASTER de implantação comissione os pontos de cada condomínio e o gestor configure regras operacionais sem receber chaves, senhas ou controles técnicos que possam interromper a integração. Uma face identifica; o direito de abrir depende de vínculo, área, sentido, categoria, idade comprovada, vigência e demais condições publicadas.

## Estado de partida e documentos

Este pacote entrega pesquisa oficial, especificação e artefatos **propostos**. Nenhuma implementação, migration, abertura real ou homologação física foi executada por esta entrega. Os modelos começam como `CANDIDATO_DOCUMENTADO`; os ensaios começam pendentes. O ZIP CondPulse v1.0 fornecido pelo usuário é base histórica de referência, não retrato confirmado da produção atual.

Leia primeiro:

1. `documentacao/CondPulse_Acesso_Facial_v1.1.md`, sobretudo capítulos 1–18 e 25–32.
2. `planejamento/backlog.json` ou `planejamento/backlog.csv`: 35 itens AC-001 a AC-035, com dependências e critérios de aceite.
3. Os contratos em `contratos/`, o desenho em `dados/`, as fontes do catálogo e o dossiê em `implantacao/`.
4. A matriz de homologação presente no pacote; preserve a distinção entre caso planejado, executado e aprovado.

## Primeira execução: auditar antes de desenvolver

Confirme o repositório e o remoto do CondPulse, leia as instruções locais e crie uma branch de trabalho. Preserve alterações existentes do usuário. Registre commit auditado, linguagem/runtime efetivos, schema, migrations aplicadas, contratos, flags, CI e topologia. Não faça mudanças no `app-advanced`.

Compare esta especificação com os componentes reais. Marque o que já existe com evidência e implemente somente as lacunas. Preserve identidade, vínculos, unidades, reservas, visitantes, credenciais, operações, inbox/outbox e auditoria que já estejam implementados. Não reaplique V001/V002 do ZIP, renumere migration aplicada ou substitua uma base existente por exemplo documental. Produza migrations aditivas e compatíveis segundo o mecanismo atual.

Comece pelos itens AC-001 e AC-002; depois siga as dependências. Control iD é a primeira prova proposta pela clareza da documentação pública. O inventário do piloto pode justificar outra ordem. Hikvision, Dahua e Intelbras possuem tarefas próprias; bloqueio de documentação de uma marca não autoriza inventar endpoints e não impede a evolução independente das demais.

## Fluxo de dados e autorização obrigatório

Sessão autenticada → contexto de tenant validado no backend → sujeito e vínculos atuais → convite/reserva/evidência etária → política publicada e ponto comissionado → decisão ou direito derivado → outbox/worker → gateway do tenant → adaptador homologado → equipamento → confirmação por destino e evento com força de evidência explícita.

O navegador não recebe segredo de fabricante; o gateway não escreve diretamente no banco central nem executa URLs/comandos arbitrários. O hardware recebe somente os direitos necessários. Cadastro facial não pode incluir automaticamente um grupo que libere todas as áreas.

Uma restrição que impeça ingresso precisa ser resolvida **antes do relé**. Evento recebido após a abertura é histórico. Se o modo for ACL local, prove a vigência e a restrição naquele firmware. Se for decisão no gateway, prove callback, prazo, negativa e contingência. O gateway/adaptador valida versões antes do envio; não presuma que um relé entende `policyVersion`.

Idade vem de evidência cadastral, nunca da imagem. Regras se aplicam a todos os métodos da mesma autorização. Um convite vencido não deve invalidar residência válida, nem um vínculo permissivo eliminar uma proibição global. Rotas residenciais essenciais, acessibilidade e saída física exigem proteção e procedimento de contingência comprovado.

## Integridade operacional

Publicação retorna job, não sucesso físico. Diferencie ACK, aplicado sem comprovação, confirmado, parcial, falha e desconhecido. Revogação vence mensagem antiga e alcança face, cartão, PIN, QR e direitos de intermediários. Dispositivo offline mantém pendência visível; mudar a UI para atendimento não desativa a lista antiga no leitor.

Abertura pontual usa contexto atual, destino explícito, validade curta e recibo durável. Timeout ambíguo, reinício ou reconexão não reexecuta comando antigo. Restore só retoma escrita após recuperar revogações posteriores ao snapshot e reconciliar o hardware; falta de diário completo mantém o gate de recuperação fechado. Backup antigo não pode ressuscitar convite expirado ou pessoa revogada.

## Gates e evidências de conclusão

| Gate | Exigência |
|---|---|
| G0 | SKU, hardware, região, firmware, API/SDK, licença e inventário identificados |
| G1 | Domínio, RBAC e isolamento com dois tenants; cenários positivos e negativos |
| G2 | Cadastro, aplicação, consulta, retirada e eventos com estados reais |
| G3 | Bancada da combinação física: pré-relé, timeout, offline, revogação e recuperação |
| G4 | Cada ponto/sentido/canal/sensor e saída comissionados no local |
| G5 | Piloto monitorado, treinamento e contingência praticada |
| G6 | Aceite técnico do MASTER e aceite das regras pelo gestor |

Simulador e teste de contrato não substituem bancada. Aprovação de um modelo não se estende a sufixo, firmware, controlador, modo ou topologia diferentes. Acionamento de porta e corte de produção seguem a janela e o procedimento do dossiê aprovado, com responsável local presente quando necessário.

## Como entregar cada incremento

Entregue PR pequeno com problema, comportamento resultante, contratos/migrations alterados, testes significativos e riscos remanescentes. Atualize o backlog apenas com evidência; não marque planejado como concluído pela existência de código. Inclua instruções reproduzíveis para homologação e retorno, sem segredos ou dados pessoais em logs, fixtures ou relatórios. Promova a mesma versão imutável validada para outros tenants, preservando regras e dados locais.
