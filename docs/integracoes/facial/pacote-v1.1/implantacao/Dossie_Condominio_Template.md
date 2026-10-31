# CondPulse - dossiê de implantação e homologação por condomínio
**MODELO PARA PREENCHIMENTO - NÃO APROVADO PARA PRODUÇÃO**  
Versão do modelo: 1.1 | Elaborado em 30/10/2026  
Este documento registra uma instalação concreta. A ficha de produto ou um ensaio de outro condomínio não substitui a validação desta topologia, deste firmware e destas regras.

## Como preencher e encerrar o dossiê

- Substituir cada **[PENDENTE]** por dado verificável, referência de evidência ou “NÃO APLICÁVEL”, com justificativa e responsável.
- Usar **PENDENTE / EM TESTE / APROVADO / REPROVADO / NÃO APLICÁVEL** como estados. “APROVADO COM RESSALVA” só pode ser usado em item não crítico, com prazo e controle compensatório aprovado.
- Uma restrição que consta apenas na tela, mas não impede o relé antes da abertura, é **REPROVADA**.
- As fotos de cadastro, segredos e cópias de documentos pessoais ficam em repositórios privados autorizados; aqui entram somente referências e evidências minimizadas.
- A aprovação desta instalação exige aceite técnico do MASTER de implantação do condomínio e aceite das regras pelo gestor responsável, usando perfil de homologação previamente aprovado pelo MASTER da plataforma. Nenhuma assinatura de negócio aprova falha técnica crítica.

**Planilhas de apoio:** os dois arquivos CSV usam UTF-8 e separador ponto e vírgula. Contêm cabeçalhos e uma única linha marcada EXEMPLO; removê-la antes de preencher dados reais. São modelos de levantamento, sem autorização para importar ou aplicar configurações automaticamente.

## 1. Identificação e escopo

| Campo | Preenchimento |
|---|---|
| Identificador do dossiê / revisão | [PENDENTE] |
| Nome e identificador do condomínio/tenant | [PENDENTE] |
| Endereço de instalação e blocos abrangidos | [PENDENTE] |
| CNPJ ou identificador administrativo, quando necessário | [PENDENTE] |
| Fuso IANA da instalação | [PENDENTE - por exemplo, America/Sao_Paulo; confirmar] |
| Tenant de homologação e conjunto de dados de teste | [PENDENTE] |
| Ambientes abrangidos: laboratório, piloto e produção | [PENDENTE] |
| Quantidade de moradores, trabalhadores e visitantes estimada | [PENDENTE] |
| Quantidade de áreas, pontos de passagem, leitores e controladores | [PENDENTE] |
| Condomínios que compartilham entradas físicas, se houver | [PENDENTE - não compartilhar cadastros por inferência] |
| Versão CondPulse, gateway e adaptadores | [PENDENTE] |
| Perfil de homologação de SKU/firmware aplicado | [PENDENTE] |
| Data de levantamento / data prevista de ativação | [PENDENTE] |
| Documentos anexos e repositório de evidências | [PENDENTE] |
| Fora do escopo, com justificativa e tratamento de risco | [PENDENTE] |

**Condição de escopo:** toda passagem capaz de alcançar uma área protegida deve constar do levantamento, inclusive saídas, garagem, elevadores, escadas, acessos de serviço, portas de emergência e trajetos acessíveis. Uma rota alternativa desprotegida não pode ficar invisível ao aceite.

## 2. Responsáveis e poderes

| Responsabilidade | Nome / vínculo / contato autorizado | Escopo e aceite |
|---|---|---|
| MASTER da plataforma CondPulse | [PENDENTE] | Homologa modelos/firmwares/adaptadores e aprova capacidades e padrões de segurança globais. |
| MASTER de implantação do condomínio | [PENDENTE] | Papel técnico com escopo neste tenant: infraestrutura, dispositivos, vínculo físico, referências de segredos e rotações delegadas, implantação e manutenção dentro dos perfis homologados. |
| Gestor/síndico que aprova o regulamento e as regras | [PENDENTE] | Pessoas, áreas de negócio, horários, convites, autorizações e exceções dentro de sua competência. |
| Operador/coordenação da portaria | [PENDENTE] | Atendimento e abertura pontual somente nos pontos e limites delegados. |
| Instalador e responsável técnico quando exigível | [PENDENTE] | Fechaduras, alimentação, circuitos, sensores, eclusas e emergência. |
| Responsável pela privacidade/contato dos titulares | [PENDENTE] | Política, solicitações, descarte e incidentes. |
| Responsável pela rede e energia | [PENDENTE] | VLAN, firewall, conectividade, UPS e continuidade. |
| Substitutos e plantão fora do horário comercial | [PENDENTE] | Escopos e vigência explícitos. |

**Matriz de permissões a validar:** gestor não vê senha, token, certificado privado ou segredo; não altera IP administrativo, serial/OSDP, firmware, vínculo de canal, trust store, timeout técnico nem configuração de emergência. Criar uma área lógica não autoriza conectar um relé físico. Nenhum dos papéis MASTER recebe automaticamente autorização de uso cotidiano das áreas ou acesso irrestrito à biometria.

**Separação de autoridade:** o MASTER da plataforma aprova o perfil de integração e suas capacidades. O MASTER de implantação aplica esse perfil no condomínio e assina o comissionamento local; não aprova por conta própria modelo, firmware ou capacidade ainda não homologados. O gestor decide as regras de uso dentro das capacidades técnicas liberadas. O título MASTER de implantação não é sinônimo de gestor/síndico.

Referência da homologação central autorizadora: **[PENDENTE]**. Aprovação de desvio/novo perfil pelo MASTER da plataforma, quando necessária: **[PENDENTE]**.

Delegação temporária de instalação: responsável **[PENDENTE]**; escopo **[PENDENTE]**; início/fim **[PENDENTE]**; motivo e revogação **[PENDENTE]**.

## 3. Regras aprovadas, privacidade e menores

| Registro exigido | Referência e responsável |
|---|---|
| Regulamento/ata/decisão que autoriza cada regra por área | [PENDENTE] |
| Restrições por idade, fundamento e validação aplicável | [PENDENTE] |
| Fontes para comprovar categoria da pessoa, vínculo e data de nascimento | [PENDENTE] |
| Processo para corrigir dado de idade ou vínculo contestado | [PENDENTE] |
| Tratamento de menores, responsável legal, acompanhamento e escopo | [PENDENTE] |
| Finalidade e base legal documentadas para biometria e logs | [PENDENTE - validar com responsável jurídico/privacidade] |
| Papéis de tratamento entre condomínio, CondPulse e prestadores | [PENDENTE] |
| Aviso de privacidade e canais de exercício de direitos | [PENDENTE] |
| Alternativa não biométrica e rota acessível equivalente | [PENDENTE] |
| Prazos de retenção: cadastro, fotos, templates, eventos, backups | [PENDENTE] |
| Transferência a equipamentos e terceiros autorizados | [PENDENTE] |
| Procedimento para término de vínculo e exclusão verificável | [PENDENTE] |
| Processo de revisão/contestação de negativa de acesso | [PENDENTE] |

A idade será calculada a partir de dado verificado e do calendário local. Não inferir idade pela aparência. “Nascimento desconhecido” é condição distinta de “menor”; definir atendimento assistido e análise, sem inventar uma data para destravar o acesso.

**Regra versus saída:** restrições de uso de uma área não podem capturar a pessoa dentro dela. Saídas de emergência e liberação de incêndio seguem o projeto aprovado e não dependem do banco, da internet ou da decisão biométrica.

## 4. Inventário técnico protegido

Preencher o arquivo `inventario_condominio_template.csv` com uma linha por equipamento físico e manter o identificador estável. No dossiê, registrar:

| Informação | Valor |
|---|---|
| Marca / SKU completo / sufixos | [PENDENTE] |
| Revisão de hardware / serial / patrimônio | [PENDENTE] |
| Firmware exato / origem e assinatura/checksum do pacote | [PENDENTE] |
| API/SDK e licença de integração aplicável | [PENDENTE] |
| Capacidade comprovada de pessoas, faces, cartões, logs e horários | [PENDENTE] |
| Ocupação atual / margem de capacidade aprovada | [PENDENTE] |
| Modos suportados de decisão e comportamento em falha | [PENDENTE] |
| Relés de acesso / relés de alarme / leitores e entradas auxiliares | [PENDENTE] |
| Proteção ambiental e limites de instalação conferidos | [PENDENTE] |
| Fonte, consumo, UPS, bateria, aterramento e proteções | [PENDENTE] |
| Equipamento/módulo reserva e procedimento de substituição | [PENDENTE] |
| Fim de suporte, garantia e contato do fabricante/integrador | [PENDENTE - não presumir] |
| Referência do segredo no cofre - secret_ref | [PENDENTE - nunca a senha] |
| Perfil de capacidade aprovado e evidência | [PENDENTE] |

**Revisão instalada é a referência:** trocar firmware, módulo, leitor auxiliar, versão do SDK ou forma de acionamento pode invalidar testes anteriores. Não tratar “mesma família” como aprovação automática.

**Segredos:** registrar proprietário, escopo, data de rotação e teste de saúde. Senhas não devem aparecer em QR de instalação entregue ao gestor, planilha, URL, captura de tela, relatório ou log.

## 5. Planta, rotas e mapeamento físico

Anexar planta revisionada **[PENDENTE]** com áreas, portas, catracas, cancelas, elevadores e rotas acessíveis. Numerar pontos físicos com placa ou etiqueta verificável.

| Área de origem → destino | Ponto / sentido | Leitor / controlador | Canal/relé | Sensor / botoeira | Rota acessível / emergência | Evidência |
|---|---|---|---|---|---|---|
| [PENDENTE] | [PENDENTE] | [PENDENTE] | [PENDENTE] | [PENDENTE] | [PENDENTE] | [PENDENTE] |

Para cada ponto:

- Porta de entrada/saída e lado do leitor: **[PENDENTE]**.
- Quem decide a autorização antes do relé: **[PENDENTE]**.
- Relação com eclusa, intertravamento, anti-passback e sensor de passagem: **[PENDENTE]**.
- Comportamento sem energia e sem rede, com leitura no equipamento: **[PENDENTE]**.
- Egresso por mecanismo físico e acionamento de emergência: **[PENDENTE]**.
- Limite de duração do pulso e prevenção de reabertura por retry: **[PENDENTE]**.
- Topologia de módulos externos, barramentos e isolamento: **[PENDENTE]**.
- Caminhos alternativos que poderiam contornar a regra: **[PENDENTE]**.
- Registro “comando aceito”, “porta aberta” e “passagem” disponível: **[PENDENTE]**.

**Regra de mapeamento:** uma área pode conter várias passagens; uma passagem liga origem, destino e sentido. O direito de usar uma área não deve ser confundido com a permissão de atravessar qualquer porta que leve a ela.

## 6. Matriz de regras de negócio por área

Preencher uma ficha para cada área. Usar grupos explícitos: morador, dependente, visitante, prestador, funcionário, gestor e emergência, com escopo por vínculo/tenant.

| Campo da regra | Preenchimento |
|---|---|
| ID da área / ID e versão da política | [PENDENTE] |
| Objetivo e documento aprovador | [PENDENTE] |
| Perfis permitidos / perfis explicitamente proibidos | [PENDENTE] |
| Idade mínima/máxima e fonte de verificação | [PENDENTE - desativada, se não aplicável] |
| Resultado se idade/categoria/vínculo estiver ausente ou expirado | [PENDENTE] |
| Vínculo com unidade, morador anfitrião e visitante | [PENDENTE] |
| Convite: responsável, validade, reentrada, limite de usos | [PENDENTE] |
| Reserva: estados aceitos, janela anterior/posterior e convidados | [PENDENTE] |
| Horários por dia, feriados e fuso | [PENDENTE] |
| Acompanhante: perfis, dupla presença e distância temporal | [PENDENTE] |
| Capacidade/lotação e fonte confiável de contagem | [PENDENTE - não presumir contagem por reconhecimento] |
| Bloqueios temporários/manutenção e precedência | [PENDENTE] |
| Credenciais alternativas sujeitas às mesmas condições | [PENDENTE] |
| Exceção: quem autoriza, motivo, duração e dupla aprovação | [PENDENTE] |
| Regra offline, prazo de validade e evidência de enforcement | [PENDENTE] |
| Mensagem de negativa e canal de atendimento | [PENDENTE] |

**Conflitos:** indicar como múltiplos vínculos e regras aplicáveis são resolvidos. Uma permissão genérica não deve neutralizar uma proibição específica aprovada. A pessoa pode ser moradora de um condomínio e visitante de outro, com decisões independentes.

**Acompanhamento:** marcar como “NÃO SUPORTADO” quando o sistema não consegue confirmar que acompanhante e acompanhado realmente atravessaram juntos. Duas leituras faciais em momentos diferentes não provam acompanhamento.

**Visitante exclusivo:** esclarecer se a regra se refere à fila/porta destinada a visitantes, à autorização de visita ou ao uso da área. A regra de uma fila não concede acesso às áreas de lazer.

## 7. Autoridade real de decisão antes do relé

| Item | Registro |
|---|---|
| Fonte de verdade dos vínculos e regras | [PENDENTE - domínio/tabelas/serviço responsável] |
| Componente que reconhece a credencial | [PENDENTE] |
| Componente que decide permitir/negar | [PENDENTE] |
| Componente que efetivamente aciona o relé | [PENDENTE] |
| Contrato e versão entre esses componentes | [PENDENTE] |
| Prova de que “negar” chega antes do acionamento | [PENDENTE] |
| Direitos presentes localmente e sua validade | [PENDENTE] |
| Fallback do dispositivo em timeout/rede/energia | [PENDENTE] |
| Permissões que não podem ser expressas localmente | [PENDENTE] |
| Ação segura quando regra não é suportada | [PENDENTE] |

**Ensaio obrigatório:** apresentar credencial conhecida, impedir a resposta do servidor e provar que a porta não abre em condição proibida. Registrar resultado físico, estado de relé/sensor, horário e versão da política. Receber “acesso autorizado” depois da abertura não permite aplicar uma negativa retroativa.

## 8. Rede, energia e emergência

| Item | Projeto / evidência / responsável |
|---|---|
| VLAN/segmentação de equipamentos, sem exposição administrativa pública | [PENDENTE] |
| Regras de firewall de entrada e saída, destinos e portas necessários | [PENDENTE] |
| Gateway local vinculado ao tenant e identidade/certificado | [PENDENTE] |
| Comunicação de saída do gateway ao CondPulse e renovação | [PENDENTE] |
| Acesso técnico remoto controlado e auditável | [PENDENTE] |
| TLS, validação de certificado, DNS e NTP | [PENDENTE] |
| IPs/reservas, inventário e ausência de conflito | [PENDENTE] |
| Canal legado não criptografado, se inevitável, e compensações | [PENDENTE] |
| Fonte/UPS/autonomia medida sob carga | [PENDENTE] |
| Comportamento das fechaduras ao perder energia | [PENDENTE] |
| Circuitos de emergência/incêndio independentes e teste local | [PENDENTE] |
| Acionamento manual assistido e acesso de socorro | [PENDENTE] |
| Comunicação alternativa entre portaria e gestão | [PENDENTE] |

A aprovação não pode depender de portas abertas indiscriminadamente na internet. Para hardware com limitação de autenticação ou transporte, registrar compensação e aceite técnico; não esconder a limitação do gestor.

## 9. Cadastro facial, sincronização e descarte

| Etapa | Responsável / evidência |
|---|---|
| Validar identidade, vínculo e finalidade antes do cadastro | [PENDENTE] |
| Informar titular e registrar autorização/base pertinente | [PENDENTE] |
| Capturar foto em canal privado com qualidade mínima | [PENDENTE] |
| Identificar duplicidade sem cruzar tenants por inferência | [PENDENTE] |
| Criar pessoa e credencial com ID técnico que não exponha documento | [PENDENTE] |
| Publicar somente nos equipamentos necessários às áreas concedidas | [PENDENTE] |
| Registrar estado individual por equipamento: pendente/aceito/falhou/revogado | [PENDENTE] |
| Ler o estado aplicado e testar a política antes de marcar “ativo” | [PENDENTE] |
| Revogar todas as credenciais alternativas ao término da autorização | [PENDENTE] |
| Excluir biometria em todos os terminais, caches e serviços aplicáveis | [PENDENTE] |
| Tratar backups, retenção residual e prova de descarte | [PENDENTE] |

Não mostrar “cadastro concluído em todos os pontos” quando um terminal está indisponível. O gestor deve ver quais áreas estão prontas e quais aguardam sincronização, sem receber credenciais técnicas.

## 10. Registro de comissionamento em campo

Antes do teste, registrar: configuração exportada **[PENDENTE]**; versão de política **[PENDENTE]**; dados de laboratório consentidos **[PENDENTE]**; rede/energia **[PENDENTE]**; janela de manutenção **[PENDENTE]**; responsável presente **[PENDENTE]**.

Cada caso deve conter **ID, ponto físico, pré-condição, ação, esperado, observado, evidência, executor, data e status**. A amostra funcional não certifica taxa estatística de erro biométrico.

| ID | Teste obrigatório | Resultado esperado | Status / evidência |
|---|---|---|---|
| C01 | Pessoa permitida no ponto correto | Apenas relé vinculado é acionado; evento correto. | PENDENTE |
| C02 | Pessoa proibida reconhecida | Negação anterior ao relé. | PENDENTE |
| C03 | Morador de outro tenant com ID local semelhante | Nenhuma autorização cruzada. | PENDENTE |
| C04 | Visitante permitido apenas na rota de recepção | Acessos posteriores restritos permanecem negados. | PENDENTE |
| C05 | Menor em área 18+ | Negação de ingresso com alternativa de atendimento. | PENDENTE |
| C06 | Idade desconhecida / alteração / aniversário no fuso local | Resultado conforme regra aprovada, sem idade estimada. | PENDENTE |
| C07 | Reserva confirmada, cancelada, expirada e janela limítrofe | Apenas janela e estado válidos permitem ingresso. | PENDENTE |
| C08 | Convite de um uso; tentativa de replay | Reuso negado segundo o estado sincronizado. | PENDENTE |
| C09 | Cartão, QR, senha e leitor auxiliar para pessoa bloqueada | Nenhuma credencial contorna a proibição. | PENDENTE |
| C10 | Servidor indisponível/timeout com pessoa local cadastrada | Fallback respeita restrição e TTL aprovados. | PENDENTE |
| C11 | Revogação com um terminal offline | Pendência visível, escalonamento e reconciliação sem falsa confirmação. | PENDENTE |
| C12 | Reinício do equipamento/gateway com comandos antigos em fila | Nenhuma abertura atrasada ou duplicada. | PENDENTE |
| C13 | Falha DNS/TLS e relógio desalinhado | Alerta e comportamento seguro documentado. | PENDENTE |
| C14 | Queda de energia e autonomia UPS | Continuidade/estado físico conforme projeto; saída segura. | PENDENTE |
| C15 | Botoeira, emergência e circuito de incêndio | Egresso/segurança independentes de internet e banco. | PENDENTE |
| C16 | Eclusa e segundo canal | Sem abertura simultânea proibida; sensores coerentes. | PENDENTE |
| C17 | Porta aberta à força, sensor defeituoso e porta retida | Alarme e atendimento; sem registrar passagem falsa. | PENDENTE |
| C18 | Comando recebido sem abertura, e abertura sem passagem | Estados separados corretamente. | PENDENTE |
| C19 | Perda/repetição/atraso de eventos | Recuperação sem duplicação nem mudança indevida de ocupação. | PENDENTE |
| C20 | Exclusão de face e troca de foto | Antiga credencial perde efeito; novo estado confirmado. | PENDENTE |
| C21 | Restauração de backup e troca por reserva | Não ressuscitar direitos revogados. | PENDENTE |
| C22 | Tentativa do gestor de acessar segredo ou configuração técnica | Negada na API e na interface, com auditoria. | PENDENTE |
| C23 | Exceção manual e abertura remota com token expirado | Somente ação autorizada vigente produz pulso, com motivo. | PENDENTE |
| C24 | Pico de entrada e população representativa | Latências e disponibilidade medidas dentro dos objetivos pactuados. | PENDENTE |
| C25 | Variação de luz/altura/acessibilidade e uso permitido de acessórios | Usabilidade avaliada e alternativa operacional disponível. | PENDENTE |
| C26 | Apresentação de foto/vídeo em ensaio autorizado | Sem aceite indevido nos cenários críticos definidos. | PENDENTE |

Defeitos críticos em C02-C16, C20-C23 ou isolamento de tenant impedem produção até correção ou redesenho que remova a função afetada. A definição final de severidade fica registrada em **[PENDENTE]**.

## 11. Offline, revogação e contingência

| Cenário | Política aprovada e responsável | Tempo limite / evidência |
|---|---|---|
| Internet indisponível; gateway e equipamentos ativos | [PENDENTE] | [PENDENTE] |
| Gateway indisponível; dispositivos ativos | [PENDENTE] | [PENDENTE] |
| Equipamento isolado, com permissões antigas | [PENDENTE] | [PENDENTE] |
| Banco/back-end indisponível | [PENDENTE] | [PENDENTE] |
| Revogação urgente ainda não aplicada | [PENDENTE] | [PENDENTE] |
| Visitante/reserva expira durante desconexão | [PENDENTE] | [PENDENTE] |
| Capacidade local cheia ou erro de cadastro | [PENDENTE] | [PENDENTE] |
| Falha de sensor, fechadura ou energia | [PENDENTE] | [PENDENTE] |
| Suspeita de credencial comprometida | [PENDENTE] | [PENDENTE] |

**Sem conectividade não existe revogação remota instantânea garantida.** O dossiê deve declarar a exposição residual e o mecanismo físico/operacional de contenção. O status “bloqueado no sistema” não deve ser exibido como “bloqueado em todas as portas” sem confirmação.

Reposição de eventos: janela máxima **[PENDENTE]**; capacidade local **[PENDENTE]**; regra de deduplicação **[PENDENTE]**. Recuperação de energia/rede: reconciliar primeiro direitos e revogações; descartar comandos de abertura vencidos.

## 12. Plano de implantação, dupla verificação e rollback

1. **Preparação:** registrar versões, backup protegido, plano físico e versões de políticas. Responsável/data: **[PENDENTE]**.
2. **Validação sem acionamento:** comparar o que o CondPulse permitiria com a regra vigente, sem considerar esse modo uma homologação de abertura. Evidência: **[PENDENTE]**.
3. **Piloto em ponto controlado:** aprovar pequena população, manter atendimento assistido e executar testes críticos. Ponto/janela: **[PENDENTE]**.
4. **Dupla verificação:** MASTER de implantação confere topologia/segurança dentro do perfil aprovado; gestor confere pessoas, regras e exceções. Assinaturas: **[PENDENTE]**.
5. **Ativação por etapas:** publicar versão, sincronizar, ler estado e testar ingresso/egresso em cada ponto. Ordem: **[PENDENTE]**.
6. **Observação inicial:** plantão, alertas e critérios de interrupção. Duração: **[PENDENTE]**.
7. **Rollback:** retornar à última configuração validada preservando todas as revogações posteriores; nunca reabrir área por restauração cega. Responsável, passos e evidência de ensaio: **[PENDENTE]**.

**Interromper implantação se:** relé errado, permissão cruzada, restrição ineficaz, saída comprometida, divergência de estado não explicada ou perda de rastreabilidade. O plano alternativo de operação humana deve estar pronto antes do primeiro acionamento.

## 13. Termo de aceite

### Aceite técnico - MASTER de implantação do condomínio

Declaro que o perfil de homologação central está válido e que o inventário, os vínculos físicos, o comportamento anterior ao relé, a segregação de credenciais, os testes críticos e o plano de continuidade foram verificados no escopo indicado, com evidências anexas. As limitações registradas não foram convertidas em promessas de capacidade.

- Nome / papel / identidade verificável: **[PENDENTE]**
- Versões aprovadas / hash ou referência da configuração: **[PENDENTE]**
- Testes e evidências que sustentam o aceite: **[PENDENTE]**
- Limitações aceitas e controles compensatórios: **[PENDENTE]**
- Data / assinatura: **[PENDENTE]**
- Estado: **NÃO APROVADO - aguardando preenchimento e testes**

### Aceite das regras pelo gestor do condomínio

Declaro que a matriz de áreas, perfis, horários, idade quando aplicável, visitantes, reservas, acompanhamento, contingência e exceções corresponde às decisões competentes do condomínio, com os registros de privacidade e acessibilidade necessários. Estou ciente de que os parâmetros técnicos e segredos permanecem sob gestão do MASTER de implantação do condomínio, conforme os padrões aprovados pelo MASTER da plataforma.

- Nome / função / competência: **[PENDENTE]**
- Documento que aprova regras e versão da matriz: **[PENDENTE]**
- Processo de contestação e atendimento: **[PENDENTE]**
- Data / assinatura: **[PENDENTE]**
- Estado: **NÃO APROVADO - aguardando validação das regras**

## 14. Suporte, mudanças e revalidação

| Item | Registro |
|---|---|
| Contatos operacionais e escalonamento técnico | [PENDENTE] |
| Metas pactuadas de disponibilidade, latência e revogação | [PENDENTE] |
| Alertas: offline, backlog, direito vencido, porta aberta, drift e capacidade | [PENDENTE] |
| Canal e prazo para incidente crítico | [PENDENTE] |
| Revisão periódica de pessoas, visitantes, administradores e segredos | [PENDENTE] |
| Janela de atualização e política de firmware | [PENDENTE] |
| Reteste após troca de dispositivo, módulo, rede, relé, firmware ou regra crítica | [PENDENTE] |
| Data da próxima revisão / responsável | [PENDENTE] |

Mudança de regra pelo gestor cria nova versão, simulação e registro de publicação. Mudança de infraestrutura pelo MASTER de implantação cria revisão técnica e pode exigir nova aprovação de campo; um novo modelo, firmware, adaptador ou capacidade retorna ao MASTER da plataforma para homologação. A interface deve mostrar a versão efetivamente aplicada em cada ponto.

---

# ANEXO - exemplo sintético de matriz de áreas
**EXEMPLO DIDÁTICO, SEM CONDOMÍNIO REAL E SEM APROVAÇÃO.** As idades, horários e TTL abaixo ilustram a modelagem; não constituem regulamento recomendado. “Assistido” significa atendimento humano com procedimento aprovado, sem destravar automaticamente a área.

## A. Áreas e políticas ilustrativas

| ID da área | Área | Política ilustrativa | Exceção / offline proposto |
|---|---|---|---|
| A01 | Entrada social de moradores | Morador/dependente com vínculo ativo; visitantes não utilizam esta fila. | Direitos locais restritos, vigência máxima proposta de 24 h, a aprovar; revogação urgente exige contenção se isolada. |
| A02 | Recepção de visitantes | Visitante com convite válido, anfitrião e janela; entrada acompanhada quando exigido. | Em falha, atendimento assistido; convite não libera lazer. |
| A03 | Academia | Morador/dependente com idade verificada ≥18 e horário 06h-22h. | Negar ingresso se informação/decisão requerida estiver indisponível; saída livre conforme projeto. |
| A04 | Piscina | Morador/dependente; exemplo de menor com responsável presente e mecanismo comprovado. | Se acompanhamento não for comprovável, atendimento assistido. |
| A05 | Salão de festas | Reserva confirmada e vigente, responsável e lista de convidados autorizada. | Lease não ultrapassa fim da reserva; cancelamento offline exige plano de contenção. |
| A06 | Espaço infantil | Exemplo de usuários infantis e responsáveis com regras próprias; não definir idade padrão global. | Política individual validada; roteiro de acompanhamento e emergência. |
| A07 | Acesso de serviço | Prestador com ordem/visita vigente e rota autorizada; demais perfis conforme matriz. | Credencial curta; após expiração, atendimento assistido. |
| A08 | Sala técnica | Funcionário/técnico nomeado, atividade autorizada e janela; visitantes e moradores sem direito genérico. | Ingresso negado sem autorização válida; egressos independentes. |

## B. Mapeamento físico ilustrativo

| Área | Ponto de passagem | Origem → destino | Leitor | Controlador/canal | Sensor e saída | Observação |
|---|---|---|---|---|---|---|
| A01 | P01 | Exterior → Hall | L01 | C01 / canal 1 | S01 / botoeira E01 | Validar rota acessível paralela. |
| A02 | P02 | Exterior → Recepção | L02 | C02 / canal 1 | S02 / E02 | Não usar relé de A01. |
| A03 | P03 | Hall → Academia | L03 | C03 / canal 1 | S03 / E03 | Testar proibição 18+ antes do pulso. |
| A04 | P04 | Jardim → Piscina | L04 | C04 / canal 1 | S04 / E04 | Sensor de porta não prova acompanhamento. |
| A05 | P05 | Hall → Salão | L05 | C05 / canal 1 | S05 / E05 | Expiração local até fim da reserva. |
| A06 | P06 | Hall → Infantil | L06 | C06 / canal 1 | S06 / E06 | Fluxo infantil separado de credencial do responsável. |
| A07 | P07 | Exterior → Serviço | L07 | C07 / canal 1 | S07 / E07 | Rotas internas também inventariadas. |
| A08 | P08 | Serviço → Sala técnica | L08 | C08 / canal 1 | S08 / E08 | Exceção técnica nominal e temporária. |

Todos os equipamentos C01-C08 deste exemplo são identificadores fictícios, sem marca, modelo ou firmware atribuídos. O exemplo não comprova que oito dispositivos sejam a solução necessária ou suficiente para uma instalação real.

