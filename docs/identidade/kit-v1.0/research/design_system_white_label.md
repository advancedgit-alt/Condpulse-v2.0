# CondPulse — especificação de identidade por cliente e governança visual

Versão proposta 1.0 · 31/10/2026 · Documento para planejamento e integração. Os objetos, endpoints e testes abaixo são especificações propostas; não significam que existam no código atual. A evidência do projeto comunicada pela auditoria orienta a adoção gradual sobre React/TypeScript/Vite e CSS próprios, mantendo Lato inicialmente.

## 1. Objetivo e limite da personalização

Permitir que administradoras e condomínios usem nome, logos e cores próprios dentro de um único produto mantido pela CondPulse. O cliente reconhece sua marca, enquanto navegação, componentes, regras de acessibilidade e operações continuam previsíveis. Um tema nunca concede permissão, muda regra de acesso, altera homologação ou dá acesso a credenciais técnicas.

O primeiro ciclo oferece identidade visual, dois modos de cor, preferências de densidade e aplicação consistente em telas e documentos selecionados. Domínio próprio, remetente de e-mail e aplicativo separado nas lojas são capacidades adicionais com implantação e operação próprias. Não devem aparecer como “prontas” porque uma logo foi substituída.

### 1.1 Níveis de oferta propostos

| Nível | Conteúdo | Controle |
|---|---|---|
| CondPulse padrão | Marca, cores, Lato e componentes oficiais | MASTER plataforma |
| Marca do cliente | Nome de exibição, logos, favicon, primária/acento e opções permitidas | Administradora/condomínio conforme permissão |
| Co-branding | Marca local e assinatura CondPulse nos pontos previstos | Política comercial e produto |
| Marca própria completa | Identidade do cliente nos pontos contratados | Contrato e capacidade técnica; responsável legal continua identificado quando necessário |
| Serviços adicionais | Domínio, remetente verificado, push, app separado | MASTER técnico, com implantação específica |

Nomes de fornecedor ou instituição que precisem aparecer por obrigação contratual ou legal não são apagados por uma opção de tema. A definição desses pontos deve ser validada na operação; o editor não aceita HTML livre.

## 2. Herança e resolução do tema

Ordem proposta: **plataforma → marca da administradora → condomínio → preferência pessoal permitida**. Administradora é opcional. Um condomínio sem administradora herda diretamente da plataforma. A preferência pessoal altera apenas claro/escuro/sistema e densidade disponível; não substitui marca, status ou identidade do condomínio.

Cada camada publica uma revisão imutável. Uma revisão efetiva do condomínio registra os identificadores exatos das revisões usadas na composição. Assim, uma mudança na marca da administradora gera uma atualização candidata, com impacto identificável, em vez de mudar silenciosamente todos os condomínios. O MASTER pode autorizar campanhas de atualização com lista explícita dos clientes afetados. O resultado precisa ser validado depois da composição, pois valores isolados corretos podem formar pares inadequados.

**Regras de resolução:**

1. Validar contexto e associação do usuário ao condomínio no servidor.
2. Resolver a política de personalização e as revisões publicadas aplicáveis.
3. Aplicar apenas chaves autorizadas; valor ausente significa herdar. `null` não apaga um recurso essencial.
4. Compilar os valores efetivos e registrar origem por token.
5. Validar esquemas, assets, contrastes e compatibilidade dos componentes.
6. Aplicar a preferência pessoal admitida pelo contexto operacional.
7. Entregar uma configuração mínima e um identificador imutável do tema efetivo.

A interface do editor mostra “Herdado da administradora” ou “Definido neste condomínio” e oferece “Restaurar herança”. Evitar deixar a origem implícita. A mudança de um usuário que opera vários condomínios precisa limpar visualmente o contexto anterior antes de buscar o novo.

## 3. Tokens e componentes

Atlassian documenta o uso de tokens nomeados por significado e a composição de temas; o CondPulse pode aplicar esse princípio ao CSS próprio sem adotar a biblioteca Atlassian. Fonte: https://atlassian.design/tokens/design-tokens/

| Camada | Exemplos | Quem edita |
|---|---|---|
| Primitivos | Paletas neutras, cores de marca, escala de espaço | Equipe do design system; marca em campos limitados |
| Semânticos | `surface.canvas`, `text.primary`, `action.primary`, `status.danger`, `focus.ring` | Compilador e política visual |
| Componentes | `button.primary.background`, `table.row.hover`, `sidebar.item.active` | Biblioteca compartilhada |
| Identidade | Nome, logo por fundo, símbolo, favicon | Usuário com permissão de marca |

Componentes consomem semântica, não HEX espalhado por página. Migração incremental: criar variáveis CSS, mapear a base auditada, substituir valores repetidos nas telas prioritárias, revisar estados e remover exceções desnecessárias. Não criar arquivo CSS ou branch de frontend por cliente.

### 3.1 O que pode ser personalizado

- Nome de exibição e nome curto, com limites e teste de truncamento.
- Logo horizontal, símbolo, versões para claro/escuro e variante monocromática; favicon.
- Cor principal da marca e cor de acento, dentro dos pares aprovados.
- Predefinição de tema claro/escuro; o usuário pode seguir o sistema.
- Fonte somente entre famílias aprovadas e licenciadas. O ciclo inicial fixa Lato.
- Assinatura de co-branding nos pontos definidos em contrato.

### 3.2 O que permanece sob controle do produto

- Semântica e textos de sucesso, alerta, erro, indisponibilidade e pendência.
- Indicador de foco, contraste, tamanho mínimo de controle e legibilidade.
- Estrutura dos formulários, nomes de campos operacionais, ações de segurança e confirmações.
- Permissões, menus autorizados, regras de negócio, integrações e segredos.
- CSS/JavaScript/HTML arbitrário, URLs de fonte e scripts externos.
- Textos legais ou identificadores de entidade quando dependem de responsabilidade formal.

A marca pode ser vermelha sem tornar todas as ações vermelhas como erro. Separar `brand.primary` de `status.danger`. Uma cor institucional clara pode funcionar no símbolo e numa superfície suave, mas exigir tom mais escuro em botão com texto branco.

## 4. Cor e acessibilidade

A meta é WCAG 2.2 AA nos fluxos concluídos. Regras centrais: texto comum 4,5:1; texto grande 3:1 conforme definição WCAG; informação não textual necessária 3:1; informação nunca só por cor; foco visível. As exceções e limites estão na norma, e a conformidade completa requer avaliação além de cor. Fontes: https://www.w3.org/TR/WCAG22/ e https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html

Os pares da base atual, calculados pela fórmula de luminância relativa sRGB do WCAG, fornecem pontos de partida: branco sobre #007F86 ≈ 4,795:1; #102A43 sobre #F4F7FA ≈ 13,618:1; branco sobre #102A43 ≈ 14,642:1. São cálculos desses pares exatos; não aprovam estados hover, transparências, imagens ou todos os elementos que usam a paleta. Não arredondar valor insuficiente para declarar aprovação.

**Pares a validar em cada modo:** texto primário/secundário contra todas as superfícies usadas; texto/ícone do botão contra normal/hover/active; link no fundo; campo/erro; badge em fundo normal e selecionado; seleção de linha e seus textos; foco contra adjacências; gráfico e legenda; placeholder quando transmite informação necessária. Disabled precisa continuar compreensível, embora o critério de contraste tenha exceções para componentes inativos.

Proposta de validação: o editor informa o par, a razão medida, a razão exigida, uma sugestão permitida e a tela afetada. Botão “Publicar” fica indisponível quando falham requisitos obrigatórios; “Salvar rascunho” permanece disponível. A validação é repetida no servidor. Não prometer conformidade a partir de um único verificador automático.

Alvos de 44×44 CSS px serão a preferência de projeto para portaria e toque. O critério AA 2.5.8 adota 24×24 CSS px com exceções; 44×44 corresponde à referência ampliada AAA 2.5.5. Fonte: https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum e https://www.w3.org/WAI/WCAG22/Understanding/target-size-enhanced.html

## 5. Pacote de marca e assets

| Arquivo lógico | Uso |
|---|---|
| `logo.horizontal.light` | Marca sobre fundo claro |
| `logo.horizontal.dark` | Marca reversa ou adaptada ao fundo escuro |
| `logo.symbol.light` e `.dark` | Menu recolhido, avatar institucional e espaço reduzido |
| `logo.mono.positive` e `.negative` | Impressão simples, gravação e reprodução monocromática |
| `logo.onBrand` | Marca sobre campo da cor institucional |
| `favicon` | Ícone de aba; variante simplificada com leitura em tamanho pequeno |
| `social.avatar` | Avatar de mídia; não confundir com favicon |

Proposta de pacote: original vetorial SVG higienizado, PDF vetorial para aplicações externas quando apropriado, PNG transparente em resoluções úteis, arquivo de licença/autoria e guia de uso. Preservar proporção e área de proteção. Não deformar a marca para caber; limitar a caixa e usar ajuste proporcional. Nome legível em texto pode substituir logo ausente.

**Upload:** MIME e conteúdo devem ser verificados; limitar tamanho em bytes e dimensões; retirar metadados desnecessários; impedir script e referências externas no SVG; rasterizar quando não houver sanitização confiável; rejeitar animação não prevista. Converter o arquivo para formatos de entrega aprovados. O usuário fornece identificação de origem e autorização de uso; essa informação não vai para a UI pública.

Assets institucionais destinados à página pública podem ser públicos, mas não podem conter nomes de moradores, biometria ou dados de operação. Associação e permissão de alteração permanecem por tenant. O recurso publicado tem URL versionada e hash de conteúdo. Não sobrescrever o conteúdo de uma URL já usada por uma revisão publicada.

Lato: https://github.com/latofonts/lato-source/blob/master/LICENSE.txt. Preservar licença e avisos na distribuição. Lucide opcional: https://lucide.dev/license; manter avisos ISC e dos ícones derivados de Feather/MIT. Não confundir permissões de uso de ícones com licença de uma marca de terceiro.

## 6. Papéis e responsabilidades propostas

Os nomes abaixo devem ser mapeados aos papéis reais do produto. A concessão é por capacidade e escopo; ocultar botão não substitui autorização no servidor.

| Capacidade | MASTER plataforma | Administradora autorizada | MASTER do condomínio | Gestor | Usuário comum |
|---|---|---|---|---|---|
| Definir baseline, tokens protegidos e política | Sim | Não | Não | Não | Não |
| Criar rascunho da marca da administradora | Sim | Sim, na própria organização | Não | Não | Não |
| Criar rascunho da marca local | Sim | Nos condomínios delegados | Sim | Se delegado explicitamente | Não |
| Pré-visualizar rascunho | Sim | No próprio escopo | Sim | Se autorizado | Não |
| Publicar marca local validada | Sim | Se delegada | Sim | Somente com capacidade explícita | Não |
| Reverter revisão local | Sim | Se delegada | Sim | Somente com capacidade explícita | Não |
| Alterar domínio/remetente e integrações | Sim/técnico autorizado | Solicita | Solicita | Não | Não |
| Alterar modo/densidade pessoal | Sim | Sim | Sim | Sim | Sim, onde disponível |

Capacidades sugeridas: `brand.read`, `brand.draft`, `brand.preview`, `brand.publish`, `brand.rollback`, `brand.policy.manage`, `brand.domain.manage`. Cada autorização contém o escopo de organização/condomínio. Gestores continuam responsáveis pelos cadastros e regras que o produto lhes delega; as permissões visuais não abrem acesso a chaves ou configurações que interrompem o sistema.

Quando um contrato exigir revisão por outra pessoa, configurar dupla aprovação. Não tornar esse fluxo obrigatório para toda mudança de cor sem necessidade. A auditoria registra quem propôs, validou e publicou.

## 7. Modelo de dados conceitual

| Entidade proposta | Campos essenciais | Integridade |
|---|---|---|
| `brand_profile` | id, owner_type, owner_id, parent_profile_id, policy_id, active_revision_id | owner é plataforma, administradora ou condomínio; impedir ciclo de herança |
| `brand_revision` | id, profile_id, number, schema_version, status, config_json, parent_revision_id, content_hash, created_by/at, approved_by/at | Revisão publicada é imutável; rascunho tem controle de concorrência |
| `brand_asset` | id, owner_scope, kind, storage_key, mime, width, height, sha256, scan_status, license_metadata | Asset pertence ao escopo correto e só é publicável após processamento |
| `effective_theme` | id, condominium_id, platform_revision_id, organization_revision_id, condominium_revision_id, compiler_version, resolved_tokens, assets_manifest, checksum | Registra combinação exata; atualização da origem não muda o registro |
| `brand_publication` | id, condominium_id, effective_theme_id, previous_theme_id, actor_id, published_at, reason, rollout_id | Troca de ponteiro ativa é atômica; histórico preservado |
| `ui_preference` | user_id, condominium_id, color_mode, density, updated_at | Só enums permitidos; sem cores ou marca arbitrária |
| `brand_domain` | id, owner_scope, hostname, verification_status, routing_status, certificate_status | Gestão técnica segregada; hostname único e normalizado |
| `brand_audit_event` | id, scope, actor_id, action, object_id, revision, correlation_id, timestamp, diff_summary | Não guardar segredos nem conteúdo de dados pessoais em log visual |

Os nomes são referenciais. Integrar ao modelo de tenant e auditoria existente em vez de duplicar usuários, condomínios ou autorização. Configurações de e-mail e domínio têm suas próprias credenciais protegidas; `config_json` contém apenas referências não secretas.

## 8. Contrato de configuração — exemplo

Arquivo correspondente: `design_system_example.json`. Os identificadores e caminhos são demonstrativos. O schema deve usar tipos, enums, limites de string, chaves explicitamente permitidas e validação de HEX/asset_id; rejeitar propriedades desconhecidas. Não aplicar JSON como CSS livre.

```json
{
  "schemaVersion": 1,
  "profileId": "brand_condominio_demo",
  "owner": { "type": "condominium", "id": "condominio_demo" },
  "inherits": { "profileId": "brand_administradora_demo", "revisionId": "rev_admin_3" },
  "identity": { "displayName": "Residencial Horizonte", "shortName": "Horizonte", "coBranding": "condpulse_signature" },
  "brand": { "primary": "#007F86", "accent": "#102A43", "fontFamilyId": "lato" },
  "assets": { "horizontalLight": "asset_logo_light", "horizontalDark": "asset_logo_dark", "symbolLight": "asset_symbol_light", "symbolDark": "asset_symbol_dark", "favicon": "asset_favicon" },
  "appearance": { "defaultMode": "system", "allowedModes": ["light", "dark", "system"], "defaultDensity": "comfortable", "allowedDensities": ["comfortable", "compact"] },
  "revision": { "id": "draft_demo_1", "status": "draft" }
}
```

O modo escuro não resulta de inverter o claro. O compilador resolve uma matriz de tokens para cada modo e publica ambos quando permitidos. Status de validação e resultados de contraste são calculados no servidor e não são confiados a um campo enviado pelo cliente. `owner` recebido em uma mutação é confrontado com o escopo autorizado; ele não define a autorização.

## 9. Interfaces de serviço propostas

| Operação | Comportamento esperado |
|---|---|
| Obter contexto autenticado | Retorna condomínio, capacidades, nome/asset autorizados e revisão efetiva; não retorna segredos |
| Obter marca pública de login | Resolve host/domínio verificado no servidor e entrega só identidade pública mínima |
| Criar/editar rascunho | Verifica capacidade e escopo; usa versão esperada para evitar sobrescrever outra edição |
| Validar revisão | Resolve herança, processa assets e verifica pares/compatibilidade; devolve erros e advertências úteis |
| Pré-visualizar | URL autenticada ou token curto, escopado e expirável; banner “Prévia”; nenhum efeito em usuários reais |
| Publicar | Exige validação da composição exata; verifica revisão ativa esperada; troca ponteiro de forma atômica |
| Reverter | Publica referência a uma revisão anterior ainda compatível e disponível; cria evento de auditoria |

Exemplo de nomenclatura: `GET /api/me/ui-context`, `POST /api/brand-profiles/{id}/drafts`, `POST /api/brand-revisions/{id}/validate`, `POST /api/brand-revisions/{id}/publish`. Estes são nomes propostos para discussão, não endpoints encontrados no repositório. Não expor um endpoint capaz de buscar configurações privadas de qualquer condomínio apenas trocando o ID.

## 10. Isolamento, cache e sessão

A OWASP recomenda contexto de tenant derivado de identidade verificada e autorização atual, com escopo de tenant em consultas, cache e armazenamento. Fonte: https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html

Aplicação proposta: o servidor valida o vínculo do usuário; URL, subdomínio ou cabeçalho ajudam a selecionar o contexto, mas não concedem acesso aos dados. Consultas e assets administrativos usam o escopo resolvido. A marca de login pode ser pública; os dados e ações do condomínio continuam autenticados.

Chave de cache recomendada para a configuração: condomínio + revisão efetiva + versão do compilador + modo, e locale apenas quando o resultado variar por idioma. ETag é derivado do conteúdo publicado. Os assets usam URLs imutáveis. Publicar invalida o ponteiro ativo e seus caches; reverter seleciona uma revisão anterior sem depender de reescrever arquivos.

No navegador, preferências são segmentadas por usuário/condomínio. Ao trocar contexto, descartar dados e tema do contexto anterior; mostrar estado neutro até resolver o novo, se não houver configuração confiável. Evitar flash da marca ou informações do condomínio anterior, inclusive ao sair e entrar com outra conta. Service worker, se existir, deve respeitar essa separação.

## 11. Fluxo de publicação e reversão

Estados propostos: rascunho → validado → aprovado, quando exigido → publicado. Alterar conteúdo após validação invalida o resultado e volta ao rascunho. Revisões substituídas ficam preservadas no histórico.

Antes de publicar, mostrar comparação claro/escuro, desktop/celular, nome e logos, uma tabela, um formulário, estados de erro e o impacto: “Esta revisão será aplicada ao condomínio X”. Uma alteração da administradora mostra quantos condomínios receberão uma candidata e quais já foram aprovados. Nunca aplicar a todos por uma seleção implícita.

Na publicação, salvar auditoria e trocar a revisão ativa na mesma transação lógica. Carregar os assets antes de ativar a configuração. Se a operação falhar, manter o tema anterior. O editor distingue “Rascunho salvo”, “Validação concluída”, “Publicado” e “Falha ao publicar”. Atualização de tema não reinicia serviços de acesso nem modifica dados operacionais.

Reversão exige permissão e confirmação da versão alvo, preserva histórico e revalida compatibilidade com o compilador atual. Se assets antigos foram retirados, oferecer revisão equivalente corrigida em vez de ativar links quebrados. Recomenda-se manter assets vinculados a revisões ativas ou reversíveis.

## 12. Desktop, portaria e celular

- Desktop administrativo: densidade confortável por padrão; compacta opcional para listas extensas; painel de detalhe preserva filtros e posição.
- Portaria: identidade do condomínio e local de abertura sempre legíveis; botões com verbos; situação do dispositivo e confirmação de resultado distinguíveis; modo escuro opcional testado em ambiente real.
- Celular: navegação reduzida por tarefa; tabelas simples viram listas; detalhes secundários não desaparecem sem alternativa; agenda tem visão em lista. Texto não é encolhido para caber em seis colunas.
- Densidade pessoal não reduz os alvos mínimos em dispositivos por toque. O contexto pode exigir confortável mesmo quando o usuário prefere compacto.
- Tipografia, zoom, leitor de tela, teclado e foco são verificados em ambas as marcas demonstradas e nos dois modos.

Não usar “homologado”, “biometria aprovada” ou “porta aberta” como mera decoração. São estados de domínio com evidência técnica. O dashboard separa solicitação, envio, confirmação, expiração e falha; a identidade visual não mascara a ausência de confirmação.

## 13. E-mail, documentos, domínio e aplicativo

E-mail recebe logo apropriada para fundo previsível, conteúdo legível sem depender de imagem e fallback de fonte compatível. Clientes de e-mail podem modificar cores; validar exemplos reais. Remetente próprio demanda configuração e verificação técnica; nenhum campo de senha fica no editor de marca.

Relatórios PDF e comprovantes recebem versão da marca aplicada, nome do condomínio, data e autoria conforme propósito. Um documento histórico deve permanecer reprodutível; não recompor automaticamente com a marca mais recente se isso alterar seu valor documental.

Domínio próprio exige comprovação de controle, DNS, certificado, roteamento, cookies, redirecionamentos de autenticação e suporte. O acesso por domínio CondPulse continua sendo um caminho operacional previsto. Nome ou ícone de aplicativo publicado em loja, push e identificador de pacote são decisões de distribuição, não efeitos automáticos do favicon. PWA e aplicativos nativos precisam de escopo e validação próprios.

## 14. Critérios de aceite

| ID | Cenário | Resultado esperado |
|---|---|---|
| WL-01 | Dois condomínios com marcas diferentes, mesmo usuário autorizado | Cada contexto apresenta a própria marca e dados; sem resíduos do anterior |
| WL-02 | Usuário tenta editar marca de tenant alheio | Servidor rejeita; tentativa auditável |
| WL-03 | Marca primária clara com texto branco insuficiente | Rascunho salva; publicação bloqueia e indica o par inadequado |
| WL-04 | Alteração só no modo claro | Modo escuro também é validado; não se aprova composição incompleta |
| WL-05 | Nova revisão da administradora | Condomínios continuam na combinação publicada até atualização explícita |
| WL-06 | Publicação concorrente | Versão inesperada é rejeitada; nenhuma edição é perdida silenciosamente |
| WL-07 | Asset inválido ou ainda não processado | Não é publicado nem executado; erro compreensível |
| WL-08 | Cache quente e troca de tenant | Sem reutilização de resposta de outro condomínio |
| WL-09 | Falha de rede ao publicar | Tema anterior permanece válido; UI não afirma sucesso |
| WL-10 | Reversão | Marca anterior reaplicada com histórico preservado e assets íntegros |
| WL-11 | Nome/logo longos, 320 CSS px e zoom | Identificação permanece útil; ações acessíveis sem sobreposição |
| WL-12 | Teclado, leitor de tela e foco | Fluxos críticos operáveis; rótulos e mensagens disponíveis |
| WL-13 | Gestor edita identidade | Não obtém acesso a segredos, dispositivos ou políticas globais |
| WL-14 | Preferência compacta no celular/portaria | Política de alvo e contexto prevalece |
| WL-15 | Exportação histórica | Documento usa a revisão de marca prevista e continua reprodutível |
| WL-16 | Theme API retorna conteúdo manipulado | Schema e allowlist rejeitam CSS, HTML, scripts e referências não aprovadas |

Conformidade visual e segurança de isolamento merecem testes de integração reais. Capturas de tela verificam regressão de layout; não substituem testes de autorização ou de teclado. Este conjunto não executa uma implantação: orienta o backlog, os contratos e os testes da implementação.

## 15. Implantação incremental sugerida

1. Catalogar os componentes e valores atuais; definir fonte, paleta e nomes semânticos com a base existente.
2. Implementar tokens e temas no shell, tabela, formulário, botões, badges e modais.
3. Criar perfis, revisões, assets e resolver de tema com autorização e cache por escopo.
4. Construir editor, validação e prévia com duas marcas fictícias.
5. Implantar publicação e reversão; exercitar os critérios de aceite.
6. Pilotar em um condomínio autorizado e perfis operacionais reais antes de ampliar.
7. Tratar domínio, remetente e aplicativo de loja como fases próprias quando contratados.

O ganho esperado é um produto reconhecível e personalizável sem multiplicar as versões do frontend. As decisões visuais continuam evoluindo por uma biblioteca compartilhada, enquanto cada cliente controla somente sua identidade e suas regras dentro das permissões delegadas.

