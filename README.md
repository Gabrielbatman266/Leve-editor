# Leve Editor

Editor de vídeo leve para Android: abre um filme, corta em cenas (estilo CapCut), dá nome/cor às cenas, monta a ordem final, coloca texto e exporta MP4.

## Como usar
1. **Novo projeto** → escolha o filme (MP4 funciona melhor).
2. **Cortar**: arraste a linha do tempo (pinça = zoom), toque em **[ Início** e **Fim ]**, ajuste pelas bordas brancas e toque em **Salvar cena**. O Início da próxima cena já fica no Fim da anterior.
3. **Cenas**: lista em ordem do filme. Toque para assistir, **+** para mandar para a Montagem, **⋮** para renomear/cor, ajustar corte, duplicar ou apagar.
4. **Montagem**: arraste pelo **≡** para mudar a ordem, toque na cena para pôr texto (posição, tamanho, cor, duração). **T Títulos** coloca "Cena 1 – Nome" em todas de uma vez.
5. **Pré-visualizar** e **Exportar vídeo** (480p/720p/1080p). O vídeo vai para *Filmes/LeveEditor*.

As cenas não copiam o vídeo: só guardam início e fim, então não ocupam espaço.

## Download
O APK é gerado automaticamente pelo GitHub Actions a cada push — veja a aba **Releases**.

Feito com Kotlin + AndroidX Media3 (ExoPlayer + Transformer).
