# WorldBackUp

마인크래프트 **26.2** 서버용 자동 백업 / 롤백 플러그인.
월드·플레이어 데이터를 일정 주기로 압축 보관하고, 테러(그리핑)를 당하면 원하는 시점으로 서버를 통째로 되돌립니다.

- 외부 라이브러리 의존성 0개 (JDK 기본 zip + Bukkit YAML만 사용)
- 압축은 비동기 스레드에서 처리 → 서버 렉 최소화
- **차등 백업** 지원 → 큰 월드에서 백업 용량·시간을 수십 분의 1로 (`backup.mode`)
- 압축이 끝나야 최종 파일명이 붙으므로 **반쯤 만들어진 백업이 정상으로 오인되지 않음**
- 복원 전에 zip 무결성을 검사해서 **깨진 백업으로 월드만 날리는 사고를 차단**
- 복원은 "예약 → 서버 종료 → 다음 부팅 시 월드 로드 전 교체" 방식이라 파일 잠금 문제 없음

---

## 1. 요구 사항

| 항목 | 값 |
|---|---|
| 서버 | Paper 26.2 (Spigot 계열 호환) |
| Java | **25 이상** (26.1부터 마인크래프트가 Java 25를 요구) |
| 빌드 | Gradle Wrapper 포함 (별도 설치 불필요) |

## 2. 빌드

```bash
./gradlew build
# 결과물: build/libs/WorldBackUp-1.0.0.jar

# 서버 plugins 폴더로 바로 복사하려면
./gradlew deployPlugin -PserverDir="D:/minecraft/server"
```

`build/libs/WorldBackUp-1.0.0.jar` 를 서버의 `plugins/` 폴더에 넣고 재시작하면
`plugins/WorldBackUp/config.yml` 이 생성됩니다.

## 3. 명령어

기본 명령어는 `/worldbackup`, 별칭은 `/wb`, `/wbackup` 입니다.

| 명령어 | 설명 | 권한 |
|---|---|---|
| `/wb backup [메모]` | 지금 즉시 백업 | `worldbackup.backup` |
| `/wb list [페이지]` | 백업 목록 (클릭하면 상세 정보) | `worldbackup.use` |
| `/wb info [ID\|번호]` | 백업 상세 정보 | `worldbackup.use` |
| `/wb restore [ID\|번호] (worlds)` | 해당 시점으로 롤백 요청 | `worldbackup.restore` |
| `/wb confirm` | 롤백 확정 (안전 백업 → 카운트다운 → 종료) | `worldbackup.restore` |
| `/wb cancel` | 롤백 요청/카운트다운 취소 | `worldbackup.restore` |
| `/wb delete [ID\|번호] (cascade)` | 백업 삭제 (`cascade` 는 딸린 차등본까지) | `worldbackup.delete` |
| `/wb lock` / `/wb unlock` | 자동 삭제 보호 설정/해제 | `worldbackup.delete` |
| `/wb prune` | 보관 정책을 지금 즉시 적용 | `worldbackup.delete` |
| `/wb status` | 다음 백업 시각, 용량, 디스크 여유 | `worldbackup.use` |
| `/wb reload` | config.yml 다시 불러오기 | `worldbackup.reload` |

ID 자리에는 `#3` 같은 목록 번호나 `latest` 도 쓸 수 있습니다.
`/wb restore [ID] worlds` 는 월드 폴더만 되돌리고 `server.properties` 등 서버 설정 파일은 유지합니다.
백업에 월드 정보가 없어 대상을 못 고르면 전체를 덮어쓰지 않고 **거부합니다.**

`cascade` 는 딸린 차등본까지 지우지만, 그 중 `/wb lock` 으로 잠근 것이 하나라도 있으면
전체를 거부합니다. 기준을 지우는 것은 곧 그 차등본을 못 쓰게 만드는 것이기 때문입니다.

권한 `worldbackup.admin` 은 위 모든 권한을 포함하며, 기본값은 모두 OP 입니다.

## 4. 테러 당했을 때 (롤백 절차)

```
1) /wb list                     -> 사고 직전 백업의 번호/ID 확인
2) /wb restore 3                -> 경고문과 대상 목록 확인
3) /wb confirm                  -> 현재 상태를 안전 백업 후 카운트다운 시작
4) 서버가 자동 종료됨
5) 서버를 다시 켬              -> 월드가 로드되기 전에 복원이 적용됨
```

- 3번 단계에서 만들어지는 `복원 직전` 백업 덕분에, 잘못 되돌렸다면 그 백업으로 다시 되돌릴 수 있습니다.
- 교체된 기존 파일은 삭제되지 않고 `plugins/WorldBackUp/replaced/<시각>/` 에 보관됩니다
  (`restore.keep-replaced-files: false` 로 끄면 바로 삭제).
  월드 전체 크기만큼 쌓이므로 `restore.keep-replaced-max` 개(기본 3개)만 남기고 자동 정리됩니다.
- 복원 결과는 콘솔과 `plugins/WorldBackUp/last-restore.yml` 에 기록됩니다.

> **자동 재시작 필수** — 플러그인은 서버 프로세스를 다시 켤 수 없습니다.
> 호스팅 패널의 자동 재시작을 켜두거나, 아래 [무인 운영](#10-무인-자동-운영) 절의 래퍼를 쓰세요.

## 10. 무인 자동 운영

관리자가 콘솔을 보지 않는 환경을 위해 두 가지 안전장치가 들어 있습니다.

**① 백업이 전멸하지 않습니다 (`retention.min-backups`)**

접속자가 없으면 백업은 건너뛰지만 보관 정리는 계속 돕니다. 그래서 서버가 `max-age-days`
보다 오래 놀면 백업이 **하나도 남지 않을 수** 있었습니다 (`keep-daily` 는 "최근 N일 안에
만들어진 백업"만 지키는데 그 기간에 백업이 없고, 자동 백업은 `protect-manual` 대상도 아님).
그 상태에서 누가 접속해 테러를 하면 되돌릴 곳이 없습니다.
`min-backups` 는 어떤 정책으로도 그 아래로 내려가지 않는 하한선입니다.

**② 복원이 실패하면 자동 작업이 멈춥니다**

복원이 중간에 실패하면 `plugins/WorldBackUp/restore-failed-<시각>.yml` 이 생기고,
이 파일이 있는 동안 **자동 백업과 보관 정리(`replaced/` 정리 포함)를 하지 않습니다.**
그러지 않으면 반쯤 복원된 월드가 계속 백업되면서 멀쩡한 예전 백업이 정책에 밀려 사라집니다.

- 수동 `/wb backup`, `/wb restore` 는 그대로 쓸 수 있습니다
- `/wb status` 맨 위에 정지 상태가 표시됩니다
- 월드를 확인한 뒤 그 파일을 지우고 `/wb reload` 하면 풀립니다
- 이후 복원이 성공하면 표식에 `.resolved` 가 붙으며 자동으로 풀립니다

**③ 서버 재시작 래퍼**

`/wb confirm` 이후 서버는 스스로 꺼지므로, 누군가 다시 켜야 복원이 적용됩니다.

<details>
<summary>Windows (<code>run-server.ps1</code>)</summary>

```powershell
$ServerDir = $PSScriptRoot
$JavaArgs  = @('-Xms4G','-Xmx4G','-jar','paper-26.2.jar','nogui')
$StopFile  = Join-Path $ServerDir 'STOP'
$DataDir   = Join-Path $ServerDir 'plugins\WorldBackUp'
$crashes   = 0

while ($true) {
    if (Test-Path $StopFile) { Write-Host '[run] STOP 파일이 있어 종료합니다.'; break }

    # 복원 예약이 걸린 채 꺼지는 것인지 미리 봐 둔다 (이건 정상 종료다)
    $restorePending = Test-Path (Join-Path $DataDir 'pending-restore.yml')
    $started = Get-Date
    & java @JavaArgs
    $ranFor = (Get-Date) - $started

    if (Test-Path $StopFile) { break }

    # 복원 실패 표식이 생겼으면 사람이 볼 때까지 멈춘다.
    if (Get-ChildItem $DataDir -Filter 'restore-failed-*.yml' -ErrorAction SilentlyContinue) {
        Write-Host '[run] 복원이 실패했습니다. 자동 재시작을 중단합니다. 월드를 확인하세요.'
        break
    }

    if ($restorePending) { Write-Host '[run] 복원 적용을 위해 재시작합니다.'; $crashes = 0; continue }

    if ($ranFor.TotalSeconds -lt 60) {
        $crashes++
        if ($crashes -ge 3) { Write-Host '[run] 크래시 루프로 판단해 멈춥니다.'; break }
    } else { $crashes = 0 }

    Write-Host '[run] 5초 후 재시작합니다.'
    Start-Sleep -Seconds 5
}
```

서버를 완전히 끄려면 `STOP` 파일을 만든 뒤 `/stop` 하세요.
</details>

<details>
<summary>Linux (systemd)</summary>

```ini
[Unit]
Description=Minecraft Paper
After=network.target
StartLimitIntervalSec=300
StartLimitBurst=5          # 5분에 5회 넘게 죽으면 중단 (크래시 루프 방지)

[Service]
Type=simple
User=minecraft
WorkingDirectory=/srv/minecraft
ExecStart=/usr/bin/java -Xms4G -Xmx4G -jar paper-26.2.jar nogui
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

`Restart=always` 라 `/stop` 으로도 다시 켜집니다. 정말 끌 때는 `systemctl stop minecraft` 를 쓰세요.
</details>

**무인 운영 권장 설정**

```yaml
backup:
  on-startup: true              # 재시작 후 상태를 한 번 남김
  skip-if-no-players: true
  directory: "D:/minecraft-backups"   # 절대 경로, 다른 물리 디스크
retention:
  min-backups: 5                # 0 으로 두지 마세요
restore:
  verify-archive: true
  create-safety-backup: true
```

> 백업본을 다른 물리 디스크·다른 장비로 복사하는 것은 이 플러그인이 하지 않습니다.
> 디스크 하나가 죽으면 월드와 백업이 함께 사라지므로, 백업 폴더를 외부로 옮기는 절차는
> 별도로 두세요.

## 5. 설정 요약 (`config.yml`)

| 키 | 기본값 | 설명 |
|---|---|---|
| `backup.mode` | `full` | `full` 또는 `differential` |
| `backup.full-every` | 24 | 차등본 N개마다 전체 백업 재생성 |
| `backup.interval-minutes` | 30 | 자동 백업 주기(분) |
| `backup.compression-level` | 4 | 0(빠름) ~ 9(최대 압축) |
| `backup.directory` | `backups` | 상대 경로면 `plugins/WorldBackUp/` 기준. **다른 디스크 권장** |
| `backup.skip-if-no-players` | true | 접속자·변경 없으면 자동 백업 생략 |
| `backup.on-shutdown` | false | 서버 종료 시 백업(종료가 느려짐) |
| `targets.worlds` | `["*"]` | 백업할 월드. 플레이어 데이터는 메인 월드 폴더에 있음 |
| `targets.extra-paths` | `[]` | `plugins/LuckPerms` 등 추가 백업 경로 |
| `targets.exclude` | 로그/캐시/lock | 백업 제외 glob 패턴 |
| `retention.max-backups` | 48 | 보관 최대 개수 |
| `retention.min-backups` | 5 | **최소 보관 개수.** 어떤 정책으로도 이 아래로 줄이지 않음 |
| `retention.max-age-days` | 14 | 보관 최대 기간 |
| `retention.keep-daily` | 7 | 최근 7일은 하루 1개씩 반드시 보존 |
| `retention.protect-manual` | true | 수동·복원직전 백업을 자동 삭제에서 제외 |
| `retention.max-protected` | 10 | 위 보호 백업의 개수 상한 (넘으면 오래된 것부터 삭제) |
| `retention.min-free-disk-gb` | 5 | 디스크 여유가 부족하면 정리 후 중단 |
| `restore.create-safety-backup` | true | 복원 직전 현재 상태 백업 |
| `restore.verify-archive` | true | 복원 전 zip 무결성 검사 |
| `restore.keep-replaced-max` | 3 | `replaced/` 스냅샷 보관 개수 |

### 차등 백업 (용량·시간 절감)

기본값은 `full` 입니다. 매번 월드 전체를 압축하므로 백업 하나하나가 독립적이고 가장 단순합니다.
월드가 크고 주기가 짧다면 `differential` 로 바꾸세요.

```yaml
backup:
  mode: differential
  full-every: 24    # 30분 주기 기준 하루 한 번 전체 백업
```

전체 백업 하나를 기준으로, 이후에는 **크기와 수정 시각이 달라진 파일만** 저장합니다.
10GB 월드라면 차등본 하나가 보통 수십~수백 MB입니다.

동작 방식:

- 각 백업 zip 에는 그 시점의 **전체 파일 목록**(`worldbackup-files.txt`)이 들어갑니다.
  복원할 때 이 목록이 정답이 되어, 차등본에 없는 파일은 기준 백업에서 꺼내 오고
  그 사이 삭제된 파일은 되살리지 않습니다.
- 기준 백업은 딸린 차등본이 모두 정리되기 전까지 **자동 삭제되지 않습니다.**
  `/wb delete` 로 직접 지우려 해도 막히고, 함께 지우려면 `/wb delete <ID> cascade` 를 씁니다.
  차등본을 압축하고 있는 동안에도 그 기준은 보관 정책·공간 확보·수동 삭제 어느 쪽으로도 지워지지 않습니다.
- 디스크 여유 검사는 **이번에 실제로 저장할 용량** 기준입니다. 차등본 하나가 200MB 라면
  월드 전체 크기가 아니라 그 200MB 로 판단하므로, 공간이 넉넉한데 백업이 거부되는 일이 없습니다.
- 기준 백업이 사라진 차등본은 `/wb list` 에 `[손상]` 으로 뜨고 복원에 쓸 수 없습니다.
- 복원 검증(`restore.verify-archive`)은 기준과 차등본 **양쪽**을 읽고, 파일 목록의 모든 항목이
  둘 중 어딘가에 실제로 있는지까지 확인합니다.
- 복원 직후 첫 백업은 기준을 새로 잡기 위해 자동으로 전체 백업이 됩니다.

> 변경 판단은 **크기 + 수정 시각** 기준입니다. 내용이 바뀌었는데 둘 다 동일한 파일은
> 감지하지 못합니다. 마인크래프트 region/playerdata 파일은 쓸 때마다 수정 시각이 바뀌므로
> 실사용에서는 문제되지 않지만, 완전한 독립성이 필요하면 `mode: full` 을 쓰세요.

### 보관 기간이 지나면 자동으로 지워지게 하려면

`retention.max-age-days` 가 그 옵션입니다. 다만 세 가지가 이 값보다 우선합니다.

- `keep-daily` — 최근 N일치는 하루 1개씩 무조건 남습니다. `max-age-days` 를 이보다 짧게 잡으려면 같이 줄이세요.
- `protect-manual` — 수동 백업과 복원 직전 백업은 나이와 무관하게 남습니다. (`max-protected` 개수 상한은 적용)
- `/wb lock` 으로 잠근 백업은 어떤 정책으로도 지워지지 않습니다.

"무조건 3일 지나면 삭제" 를 원한다면:

```yaml
retention:
  max-backups: 0
  max-age-days: 3
  keep-daily: 0
  protect-manual: false
```

정리는 **서버 시작 시, 백업 성공 후, 자동 백업을 건너뛴 주기마다** 자동으로 돌고, `/wb prune` 으로 즉시 실행할 수도 있습니다.

## 6. 백업에 포함되는 것

- 월드 폴더 전체 (`region`, `entities`, `poi`, `data`, `level.dat`)
- **플레이어 데이터** — 인벤토리·좌표·경험치(`playerdata`), 통계(`stats`), 발전 과제(`advancements`)

  > 이 폴더들의 위치는 서버 구현·버전에 따라 다릅니다. 메인 월드 폴더 안일 수도, 서버 루트일
  > 수도 있어서 플러그인이 **양쪽을 모두 찾아** 자동으로 백업에 넣습니다.
  > 못 찾으면 서버 시작 시 콘솔에 경고가 뜨고, `/wb status` 에 <b>미포함</b>으로 표시되며,
  > 백업할 때마다 다시 경고합니다. 조용히 빠지지 않습니다.
  > (그때는 `targets.extra-paths` 에 실제 경로를 넣어 주세요.)
- `ops.json`, `whitelist.json`, `banned-players.json`, `usercache.json`, `server.properties` 등 서버 파일
- `targets.extra-paths` 에 지정한 플러그인 데이터 폴더

> `extra-paths` 에 이미 백업되는 월드의 하위 경로(`world/playerdata` 등)를 적어도 괜찮습니다.
> 겹치는 대상은 상위 경로 하나로 합쳐지고, 어떤 항목이 합쳐졌는지 콘솔에 남습니다.

## 7. 동작 방식 / 주의사항

**백업**
1. 메인 스레드에서 `savePlayers()` → 각 월드 `save()` → 자동 저장 OFF
2. 비동기 스레드에서 `wb-<ID>.zip.tmp` 로 압축 (진행률을 5초마다 콘솔 출력)
3. 압축이 정상적으로 끝나야 `wb-<ID>.zip` 으로 이름을 바꿉니다
4. 메인 스레드에서 자동 저장 원복 → 보관 정책에 따라 오래된 백업 정리

자동 저장 원복은 백업이 어떤 이유로 실패하든(타임아웃 포함) 반드시 실행되고,
서버 종료 시점에도 한 번 더 확인합니다. 자동 저장이 꺼진 채 방치되면 크래시 시
그 세션 전체가 날아가기 때문입니다.

**복원**
- 서버 실행 중에는 region 파일이 열려 있어 교체가 불가능하므로,
  `pending-restore.yml` 예약 파일을 남기고 서버를 종료합니다.
  예약 파일은 **실제로 종료하기 직전에** 기록되므로, 카운트다운 도중 서버를 강제로 꺼도
  의도치 않은 복원이 일어나지 않습니다.
- 다음 부팅 시 플러그인 `onLoad()`(월드 로드 직전)에서
  ① zip 무결성 검사 → ② 대상 폴더 비우기 → ③ 압축 해제 순으로 진행합니다.
  ①에서 실패하면 **기존 월드를 전혀 건드리지 않고** 중단합니다.
- 백업에 내용이 없는 경로(백업 시점에 비어 있던 `extra-paths` 폴더 등)는 **비우지 않고 건너뜁니다.**
  비우기만 하고 채우지 않으면 그 폴더가 사라지기 때문입니다. 콘솔에 어떤 경로를 건너뛰었는지 남고,
  모든 대상에 내용이 없을 때만 복원을 거부합니다.
- 복원이 중간에 끊기면 `restore-failed-<시각>.yml` 이 생기고 **재시도 루프에 빠지지 않습니다**.
  이 경우 서버를 끄고 `replaced/` 폴더를 이용해 수동 확인이 필요합니다.

**알아 두면 좋은 점**
- 백업 중 자동 저장을 꺼도 청크 언로드, 플레이어 퇴장 시 `playerdata` 기록, 다른 플러그인의
  파일 쓰기는 계속됩니다. 즉 **완벽한 스냅샷은 아닙니다.** region 파일은 청크 단위 구조라
  피해가 국소적이지만, 완전한 정합성이 필요하면 파일 시스템 스냅샷(LVM/ZFS/VSS)을 쓰세요.
- 압축 도중 서버가 죽으면 `.zip.tmp` 만 남고, 다음 시작 시 자동으로 정리됩니다.
  혹시 손상된 백업이 목록에 보이면 `/wb list` 에 <b>[손상]</b> 으로 표시되고 복원에 사용할 수 없습니다.
- 메인 월드의 `level.dat`(시간·날씨·게임룰 등)은 서버가 부팅 초기에 이미 메모리로 읽어 두기 때문에,
  복원 직후 한 세션 동안은 이전 값이 유지될 수 있습니다. 지형·건축물·플레이어 데이터는 영향받지 않습니다.
- 백업 중 잠긴 파일(`session.lock` 등)은 건너뛰며, 건너뛴 개수는 콘솔에 표시됩니다.
- 백업 폴더가 서버 폴더 안에 있으면 자기 자신을 백업하지 않도록 자동으로 제외합니다.
- 백업본은 **다른 물리 디스크**에 두는 것을 권장합니다 (`backup.directory` 에 절대 경로 지정).
- 블록 단위로 "누가 무엇을 부쉈는지" 되돌리려면 CoreProtect 같은 로그 기반 플러그인을 함께 쓰세요.
  이 플러그인은 서버 전체를 특정 시점으로 되돌리는 용도입니다.

## 9. 서버 호환성

| 항목 | 상태 |
|---|---|
| Paper 26.2 | 지원 |
| 명령어 | Brigadier 로 등록합니다. 인자 타입과 권한이 인자 단위로 검증됩니다 |
| 스케줄러 | `AsyncScheduler` / `GlobalRegionScheduler` (현대 Paper API) |
| Folia | **미지원.** 로드되지 않습니다 |

스케줄링은 [`util/Sched.java`](src/main/java/io/github/yj/worldbackup/util/Sched.java) 한 곳에 모아
두어 Folia 이관 시 손볼 범위를 좁혔습니다. 다만 Folia 는 월드 저장이 리전별로 쪼개져 있어
이 플러그인의 전제인 "전 월드를 동시에 얼린 스냅샷"이 성립하지 않습니다. 스케줄러만 바꿔서는
안 되고 백업 로직 자체를 다시 설계해야 하므로, 검증 없이 `folia-supported` 를 선언하지 않았습니다.

플러그인 로딩은 `plugin.yml` 방식을 유지합니다. `paper-plugin.yml` 로 옮기면 로딩·클래스로딩
모델이 바뀌는데, 이 플러그인의 핵심 보장인 "복원은 월드 로드 전에 실행된다"가 여기에 달려 있어
실서버 검증 없이는 건드리지 않았습니다.

## 8. 테스트

```bash
./gradlew test
```

- `BackupRestoreRoundTripTest` — 백업 → 지형 삭제/인벤토리 초기화/파일 추가(테러 재현) → 복원 → 원상복구 검증,
  중단된 복원의 무한 루프 방지, zip slip 방어, 차등 백업의 스냅샷 정확성(삭제된 파일·빈 폴더가 되살아나지 않는지)
- `BackupRetentionTest` — 보관 정책. 나이/개수 상한, `keep-daily`, 수동 백업 보호와 `max-protected` 상한,
  `/wb lock` 의 절대 보호, 차등본이 살아 있는 기준 백업 보존, 남은 임시 파일 정리
- `GlobMatcherTest` — 제외/보존 패턴 매칭 (윈도우 `\` 경로 포함)
- `FileUtilTest` — 겹치는 백업 대상 병합, 상대 경로 변환
