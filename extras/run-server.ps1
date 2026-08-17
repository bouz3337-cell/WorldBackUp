# WorldBackUp - 윈도우용 서버 재시작 래퍼
#
# /wb confirm 이후 서버는 스스로 꺼집니다. 누군가 다시 켜야 복원이 적용되므로,
# 관리자가 콘솔을 보지 않는 서버에는 이런 래퍼가 필요합니다.
#
# 쓰는 법: 서버 폴더에 두고 이 파일을 실행하세요.
#   - 서버를 완전히 끄려면: 서버 폴더에 STOP 이라는 빈 파일을 만든 뒤 /stop
#   - 복원이 실패하면 자동 재시작을 멈춥니다 (사람이 월드를 확인해야 하므로)
#   - 1분 안에 세 번 죽으면 크래시 루프로 보고 멈춥니다

$ServerDir = $PSScriptRoot
$JavaArgs  = @('-Xms4G', '-Xmx4G', '-jar', 'paper-26.2.jar', 'nogui')
$StopFile  = Join-Path $ServerDir 'STOP'
$DataDir   = Join-Path $ServerDir 'plugins\WorldBackUp'
$crashes   = 0

Set-Location $ServerDir

while ($true) {
    if (Test-Path $StopFile) { Write-Host '[run] STOP 파일이 있어 종료합니다.'; break }

    # 복원 예약이 걸린 채 꺼지는 것인지 미리 봐 둔다. 그건 정상 종료다.
    $restorePending = Test-Path (Join-Path $DataDir 'pending-restore.yml')
    $started = Get-Date
    & java @JavaArgs
    $ranFor = (Get-Date) - $started

    if (Test-Path $StopFile) { Write-Host '[run] STOP 파일이 있어 종료합니다.'; break }

    # 복원 실패 표식이 생겼으면 사람이 볼 때까지 멈춘다. 그대로 다시 켜면 반쯤 복원된 월드가
    # 계속 돌아가고, 플러그인이 걸어 둔 자동 백업 정지도 의미를 잃는다.
    if (Get-ChildItem $DataDir -Filter 'restore-failed-*.yml' -ErrorAction SilentlyContinue) {
        Write-Host '[run] 복원이 실패했습니다. 자동 재시작을 중단합니다. 월드를 확인하세요.'
        break
    }

    if ($restorePending) {
        Write-Host '[run] 복원 적용을 위해 재시작합니다.'
        $crashes = 0
        continue
    }

    if ($ranFor.TotalSeconds -lt 60) {
        $crashes++
        if ($crashes -ge 3) { Write-Host '[run] 크래시 루프로 판단해 멈춥니다.'; break }
    } else {
        $crashes = 0
    }

    Write-Host '[run] 5초 후 재시작합니다.'
    Start-Sleep -Seconds 5
}
