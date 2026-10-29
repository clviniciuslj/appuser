# Real Ping (Android)

App Android para ping, traceroute, diagnóstico de conexão e teste de velocidade, feito para uso em campo.

## Abas

**Terminal**
- Ping contínuo ou com quantidade fixa, para um ou até 4 alvos ao mesmo tempo (separados por vírgula).
- Gráfico de latência em tempo real, estatísticas (mín, méd, máx, jitter, perda e quedas) e console.
- Traceroute com 3 sondas por salto.
- Favoritos, histórico e atalhos para o gateway e o DNS da rede atual.
- Enviar o resultado como texto ou imagem, ou copiar o console.

**Diagnóstico**
- Verificação em etapas: conexão, IP (DHCP), roteador, internet, DNS, navegação, IP público e rota.
- Teste de velocidade (download, upload, ping e jitter) com velocímetro.

**Widget** (tela inicial): pílula com o IP de destino, botão ▶/■ e o resultado mais recente. Tocar no campo de IP abre uma janelinha para digitar (widgets do Android não aceitam campo de texto). O ping do widget e o do app são **a mesma sessão**: ela vive no `PingHub`, roda num serviço em primeiro plano (com notificação) e continua com o app fechado. Abrir o app durante o teste mostra o mesmo ping, com o histórico, sem reiniciar. Parar no widget ou no app para a mesma sessão.

**Opções** (botão ⋮ no topo): manter a tela ligada durante o ping e vibrar quando a conexão cair e voltar.

## Sintaxe do campo de alvo

| Exemplo | O que faz |
|---|---|
| `172.16.1.1` | Ping contínuo |
| `172.16.1.1 -n 10` | 10 pacotes (`-c` também funciona) |
| `172.16.1.1 -l 1472` | Pacotes de 1472 bytes (`-s` também funciona) |
| `8.8.8.8, 1.1.1.1` | Vários alvos ao mesmo tempo (até 4) |

O app usa o `/system/bin/ping` do próprio Android; as respostas vêm do processo real.

## Gerar o APK

O workflow `.github/workflows/build-apk.yml` compila a cada push na `main` (ou manualmente em **Actions → Build Android APK → Run workflow**). Ao terminar, baixe o artefato **PingReal-APK** e instale o `app-debug.apk`.

O APK é assinado sempre com a mesma chave (`app/pingreal.keystore`), então cada versão nova instala por cima da anterior, sem desinstalar e sem perder favoritos e histórico.

## Versões

A cada mudança, subir em `app/build.gradle.kts`:
- `versionCode` em 1 (2, 3, 4…)
- `versionName` (1.1, 1.2, 1.3…)

A versão instalada aparece no topo do app, ao lado do nome.
