# Roteiro de testes CondPulse HML

Use https://project-rhvdq.vercel.app com a própria conta e segundo fator. Não trocar o banco de produção nem criar chaves novas. Os dados de01/11/2026 ficam preservados até pedido posterior.

## Percurso disponível para a conta atual

1. Em Meus acessos, escolha Condomínio Teste CondPulse (homologação). A conta já tem gestão e morador; a gestão exige AAL2.
2. Gestão → Pedidos: abra TESTE HML 20261101 — manutenção sintética. Conferir concluído, prazo03/11, conversa pública, comentário interno e anexos válido/recusado. O comentário interno só aparece na gestão.
3. Morador → Início: abra o mesmo pedido. Conferir resolução e mensagem pública, ausência do texto interno, aviso na caixa e registro de abertura. O pedido concluído permanece no histórico e não deve contar como “em andamento”.
4. Ainda em Início, abrir TESTE HML 20261101 — aviso de homologação. O sistema registra abertura na aplicação; não é ciência jurídica.
5. Reservas: salão em03/11/2026 10h–11h, limpeza até11h30, com convidado sintético. A tentativa de04/11 foi recusada por cota e não criou outra reserva.
6. Acessos: visitante sintético de02/11 10h–12h e convidado vinculado à reserva. Não compartilhar links/códigos com pessoas reais.
7. Trocar condomínio: a unidade101 do primeiro não pode ser aberta no segundo. O teste de URL cruzada já foi recusado.

Não apagar anexos, cancelar a reserva ou resetar o demo apenas para “limpar a tela”: a carga é a evidência para revisão.

## Demo carregado

Na Central → Condomínio demo há geração1 com2torres/32unidades/3áreas, pessoas e parceiro fictícios, avisos, encomendas, visitas, ocorrências, pedidos, escalas e log. Nenhuma conta real foi vinculada automaticamente.

A conta atual já abriu a gestão do demo e a gestão do parceiro com AAL2, após vínculos especificamente confirmados no chat. Pelo parceiro, Equipe mostra7 registros; Carteira mostra somente o contrato do demo; Escala mostra4 escalas fictícias. Consulte o Log de Segurança sob pedido e use Verificar integridade; cada leitura grava evento. A correção das rotas HTTP está no PR #176 e os recibos de publicação estão em ESTADO_ATUAL.

Portaria: o convite foi aceito, mas está PENDING. Outro gestor precisa aprovar em Gestão → Pessoas → Situação Pendente, com sua própria conta. A tentativa pela mesma conta foi recusada por MEMBERSHIP_SELF_DECISION_DENIED. Não aprovar por SQL ou fabricar ator. A gestão dessa segunda conta ainda precisa de autorização específica.

Morador: ainda não há vínculo real no demo. Confirmar primeiro a unidade fictícia exata (proposta: Torre A,101); o controle automático bloqueou o convite porque a unidade não tinha sido indicada. Após confirmação, criar e aceitar o convite e obter aprovação de outro gestor. Operador/supervisor do parceiro também exigem papel e escala próprios; a confirmação de Gestor do parceiro não concede esses papéis adicionais.

## Ainda não aceito

Oferta/aceite real da fila, alertas reais de permanência e funções dos perfis ainda sem vínculo; desempenho da hospedagem, recuperação completa, aparelho/hardware e restauração. Fila e alertas já estão ligados no worker e têm testes JDBC, mas isso não substitui uma jornada real.

Recibos, IDs e limites em [ESTADO_ATUAL.md](ESTADO_ATUAL.md).
