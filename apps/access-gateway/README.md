# apps/access-gateway — gateway de acesso facial do condomínio

Documentação completa: [`docs/integracoes/facial/GATEWAY.md`](../../docs/integracoes/facial/GATEWAY.md).

**Simulador não é homologação.** Os testes deste módulo usam servidores HTTP falsos que imitam respostas documentadas
pelos fabricantes e um adaptador programável para o núcleo. Passar nos testes não aprova nenhum equipamento, firmware
ou modo: isso exige bancada (G3) e comissionamento (G4) com o equipamento real.

```sh
mvn -B -ntp -f apps/access-gateway/pom.xml verify
```

Pacotes: `spi` (contrato comum e tipos), `config` (configuração sem segredos e cofre), `capability` (registro por
combinação), `queue` (fila idempotente), `open` (abertura pontual), `events` (fila de eventos), `decision` (decisão
local antes do relé), `inbound` (entrada autenticada por token do equipamento), `central` (cliente da API central),
`runtime` (núcleo), `adapters/{controlid,hikvision,dahua,intelbras}`.
