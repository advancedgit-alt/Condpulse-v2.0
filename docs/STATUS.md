# Estado verificável — 27/10/2026

## Integrado
- Fundação revisada e integrada pela PR#1, commit b933891.
- JWT sem audiência rejeitado com teste; CI acompanha pushes de todas as branches.
- Supabase rpnrqfrfwywglvyykgrg ativo em sa-east-1, PostgreSQL17.11.0.003.
- V001/V002 executadas atomicamente na migration nativa20261027225434, conforme ADR-0003.
- 13 tabelas com ENABLE/FORCE RLS;124colunas,101constraints,14policies,37índices atestados.
- cp_api/cp_worker: NOLOGIN, sem SUPERUSER/BYPASSRLS/CREATEROLE/CREATEDB.
- Manifesto/hashes em infra/supabase/bootstrap/foundation-manifest.json.
- Auth existente preservado; conta técnica confirmada reconciliada sem criar vínculos de condomínio.
- Buckets privados cp-documents(PDF20MiB), cp-parcel-photos(JPEG/PNG10MiB) e cp-upload-quarantine(PDF/JPEG/PNG20MiB), sem políticas públicas.
- Auth: proteção contra senhas vazadas, troca segura, senha atual na alteração, mínimo12caracteres e OTP900s. E-mail confirmado exigido e acesso anônimo desligado. A senha do usuário não foi alterada.
- Security advisor retornou lints=[].

## Em implementação
- BFF: Auth real, cookie opaco Secure/HttpOnly/SameSite, tokens cifrados, CSRF/Origin, refresh lease/CAS e logout durável. Desligado até configuração/homologação.
- V003 e novos testes aguardam CI/revisão; não aplicada remotamente nesta fotografia.
- Contrato OpenAPI atualizado; web/mobile ainda sem interface implementada.

## Gates e limites
- Fundação:10testes Java,4Node,25PostgreSQL e smokeHTTP passaram no CI40597598773.
- Resultados BFF devem ser conferidos na PR; não constituem aceite de produto.
- Teste de runtime via conector não executado: postgres não possui SET no grupo cp_api; nenhum privilégio foi ampliado para contornar isso. Futuro login/JDBC exige ensaio próprio.
- Baseline Flyway oficial ainda não executado. ADR-0003 exige atestação e baseline002 enquanto apenas V001/V002 estiverem aplicadas; nunca fabricar histórico.
- Terminal funciona, mas DNS externo bloqueia GitHub/npm. Código obtido pelo conector; build Java em CI remoto. Navegador voltou a funcionar.
- Aplicação não implantada. Host dedicado, domínio, SMTP, cofre, segundo ambiente, PITR/restore, MFA operacional, P0/P1, carga e integração física pendentes.
- Sem dados de demonstração persistidos nem alterações na Advanced. Os60cenários de aceite de produto continuam não concluídos.

