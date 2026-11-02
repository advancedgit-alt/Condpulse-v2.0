# Avisos de permanência — ADR-0014, V058

Extensão em implementação, sem aceite de publicação/homologação neste documento. Consulte ESTADO_ATUAL.md para a versão real da HML.

Quando o worker registra N1 de permanência, uma tarefa STAY_OVERDUE nasce na mesma transação. A chave condomínio/permanência impede duplicação, inclusive em nova passada. O outbox produz uma notificação IN_APP para moradores/responsáveis vigentes da unidade correta; dependentes sem autoridade, outra unidade, conta/vínculo encerrado não recebem esse aviso.

O aviso não contém nome/documento nem afirma que a pessoa está presente. Diz que o prazo venceu sem registro de saída e orienta contatar a portaria para localizar/solicitar saída. A tela autenticada de acessos consulta os detalhes com as permissões atuais.

Se a permanência foi encerrada, justificada ou o controle desligado antes do processamento, a tarefa fica SKIPPED. Disponibilidade na caixa não é leitura: somente a abertura autenticada grava READ_IN_APP. Repetição do worker não entrega de novo. Payload com unidade diferente da fonte é recusado no serviço e no banco.

Este consumidor é da caixa interna web/mobile. Não envia e-mail, SMS ou push para o sistema operacional e não anuncia esses canais como entregues. O recebimento com aplicativo fechado depende do consumidor externo e do registro de dispositivo, que exigem sua própria implementação/validação.

Os testes usam PostgreSQL/worker reais descartáveis e expiração do relógio real, incluindo saída antes da entrega, repetição e falsificação de unidade. A jornada Chromium confirma que apenas listar não marca leitura e que abrir leva à conferência da permanência. A execução efetiva deve constar no CI/recibo da PR.
