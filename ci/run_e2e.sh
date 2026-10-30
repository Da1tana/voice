#!/usr/bin/env bash
# Запускается внутри android-emulator-runner. Поднимает backend с имитацией Claude и гоняет e2e.py.
set -u
APK="${1:-apk/app-debug.apk}"

nohup python3 ci/mock_backend.py > ci-mock-backend.log 2>&1 &
for i in $(seq 1 30); do curl -sf http://127.0.0.1:8000/health > /dev/null && break; sleep 1; done
curl -s http://127.0.0.1:8000/health; echo

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell getprop ro.build.version.release
adb shell wm size

python3 -u ci/e2e.py "$APK" | tee e2e.log
code=${PIPESTATUS[0]}

# Результаты в аннотации: так их видно через API, даже без доступа к полным логам.
# GitHub показывает не больше 10 аннотаций одного уровня на шаг, поэтому сводка — одной аннотацией.
summary=$(grep '^RESULT|' e2e.log | cut -d'|' -f2- | sed 's/%/%25/g' | awk 'BEGIN{ORS="%0A"} {print}')
echo "::notice title=E2E results::${summary}"
grep '^RESULT|[^|]*|FAIL|' e2e.log | head -9 | while IFS='|' read -r _ name status detail; do
  echo "::error title=E2E FAIL: ${name}::${detail//%/%25}"
done
mkdir -p ci-out && cp ci-mock-backend.log e2e.log ci-out/ 2>/dev/null
exit $code
