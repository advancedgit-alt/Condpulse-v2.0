# Web — contrato de implementação
React/TypeScript estrito/Vite/React Router/TanStack Query.
Interface ainda pendente, sem versão compilada/lockfile.
Rotas previstas: entrar, contextos, morador (Início/Acessos/Reservas/Mais),
portaria (Agora/Previstos/Encomendas) e gestão (Aprovações/Configuração).
BFF/cookie HttpOnly e CSRF; refresh token nunca em localStorage.
Cache inclui conta/tenant/unidade; troca cancela requests e limpa contexto anterior.
Sem confirmação otimista nas operações de acesso, retirada, aprovação ou reserva.
Alvos48px, Lato/fallback, foco/teclado/zoom200% e estados completos do PRD.
Não simular dados reais para apresentar sistema implantado.
