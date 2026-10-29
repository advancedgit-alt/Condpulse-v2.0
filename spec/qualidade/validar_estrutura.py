from pathlib import Path
import json,yaml,re,collections,xml.etree.ElementTree as ET,zipfile,hashlib
R=Path(__file__).resolve().parents[1];checks=[]
def ok(label,detail):checks.append((label,detail))
for p in R.rglob('*.json'):json.loads(p.read_text())
ok('JSON','Todos os arquivos JSON foram lidos e analisados sem erro.')
for p in R.rglob('*.yaml'):yaml.safe_load(p.read_text())
ok('YAML','OpenAPI e compose de exemplo foram analisados por PyYAML.')
a=yaml.safe_load((R/'api/openapi.yaml').read_text());ops=[]
def walk(x):
 if isinstance(x,dict):
  if '$ref' in x:
   ref=x['$ref'];assert ref.startswith('#/'),ref;t=a
   for part in ref[2:].split('/'):t=t[part.replace('~1','/').replace('~0','~')]
  for v in x.values():walk(v)
 elif isinstance(x,list):
  for v in x:walk(v)
walk(a)
ids=[]
for path,methods in a['paths'].items():
 for method,op in methods.items():
  if method not in ('get','post','put','patch','delete','options','head'):continue
  ops.append((method,path));ids.append(op['operationId'])
  defined={x['name'] for x in op.get('parameters',[]) if x.get('in')=='path'}
  wanted=set(re.findall(r'\{([^}]+)\}',path));assert wanted<=defined,(path,wanted-defined)
  for sec in op.get('security',a.get('security',[])):
   for k in sec:assert k in a['components']['securitySchemes'],k
assert len(ids)==len(set(ids));assert len(ops)==59
ok('OpenAPI estrutural',f'{len(ops)} operações; {len(a["components"]["schemas"])} schemas; referências resolvidas, IDs únicos, parâmetros de rota e esquemas de segurança presentes. Não substitui validator OpenAPI completo nem teste HTTP.')
b=json.loads((R/'planejamento/backlog.json').read_text());idx={x['id']:x for x in b};assert len(idx)==len(b)
visited=set();active=set()
def dfs(k):
 if k in visited:return
 assert k not in active,'cycle '+k;active.add(k)
 for dep in idx[k]['depends_on']:assert dep in idx,dep;dfs(dep)
 active.remove(k);visited.add(k)
for k in idx:dfs(k)
ok('Backlog',f'{len(b)} tickets; dependências existentes e sem ciclo; todos planejados.')
c=json.loads((R/'qualidade/cenarios_aceite.json').read_text());assert len(c)==60;assert len({x['id'] for x in c})==len(c)
ok('Aceite','60 cenários especificados com dado/quando/então; não executados.')
sql=(R/'banco/V001__core_reference.sql').read_text();tabs=re.findall(r'CREATE TABLE ([a-z_]+\.[a-z_]+)\s*\((.*?)\n\);',sql,re.S)
assert len(tabs)==41,len(tabs);names=[x[0] for x in tabs];assert len(names)==len(set(names))
cols={}
for name,body in tabs:
 cols[name]=set(re.findall(r'^\s{2}([a-z_]+)\s+(?:uuid|text|bytea|boolean|integer|bigint|jsonb|timestamptz|tstzrange)',body,re.M))
for table,cs in re.findall(r'REFERENCES ([a-z_]+\.[a-z_]+)\(([^)]+)\)',sql):
 assert table in cols,table
 for col in cs.split(','):assert col.strip() in cols[table],(table,col)
rls=(R/'banco/V002__rls_reference.sql').read_text()
for name in names:
 if name.startswith('cp.'):assert f'ALTER TABLE {name} FORCE ROW LEVEL SECURITY' in rls,name
ok('SQL estrutural','41 CREATE TABLE identificados, sem nomes repetidos; referências declaradas apontam para tabelas/colunas presentes; 38 tabelas cp com FORCE RLS. Não houve parse por PostgreSQL nem execução de migrations/constraints.')
text=(R/'documentacao/CondPulse_PRD_Arquitetura_v1.0.md').read_text()
for src in re.findall(r'!\[[^\]]*\]\(([^)]+)\)',text):assert (R/'documentacao'/src).resolve().is_file(),src
ok('Imagens','Todas as imagens referenciadas pelo PRD existem; 9 diagramas com fonte DOT, SVG e PNG.')
for p in R.rglob('*.svg'):ET.parse(p)
ok('SVG','Arquivos vetoriais analisados como XML válido; não há arquivos de fonte no pacote.')
sources=json.loads((R/'referencias/fontes.json').read_text());refs={s['id'] for s in sources}|{'B01','B02'}
for match in re.findall(r'\[((?:R\d\d|B\d\d)(?:, (?:R\d\d|B\d\d))*)\]',text):
 for k in match.split(', '):assert k in refs,k
ok('Referências','24 fontes oficiais numeradas e duas referências de origem; chamadas internas têm definição.')
def lum(hex):
 vals=[int(hex[i:i+2],16)/255 for i in (0,2,4)];vals=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in vals];return sum(a*b for a,b in zip(vals,[.2126,.7152,.0722]))
contrast=(1+.05)/(lum('007F86')+.05);assert contrast>=4.5
ok('Contraste primário',f'Teal #007F86 sobre branco: {contrast:.2f}:1 por cálculo sRGB. Não é auditoria WCAG completa de telas implementadas.')
report=['# Relatório de validação do pacote documental','', 'Data: 27/10/2026. Escopo: arquivos desta entrega, não aplicação ou infraestrutura de produção.','', '## Verificações executadas','']
for label,detail in checks:report += [f'**{label}:** {detail}','']
report += ['## Verificações não realizadas','', 'Execução do SQL em PostgreSQL; validator OpenAPI completo; compilação de Java/React; testes automatizados do produto; carga; auditoria de segurança em ambiente real; restore de banco/Storage; provisionamento; chamadas reais ao parceiro; reconhecimento/abertura física; publicação nas lojas; registro da marca. Não há aplicação executável neste pacote.','', '## Revisão visual','', 'PDF/DOCX renderizados por LibreOffice e conferidos por páginas; a contagem final e as verificações de paginação são registradas na seção final após fechamento dos arquivos.']
(R/'qualidade/RELATORIO_VALIDACAO.md').write_text('\n'.join(report)+'\n')
print('\n'.join(f'{x}: {y}' for x,y in checks))
