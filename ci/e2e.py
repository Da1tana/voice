"""Сквозной тест приложения на эмуляторе Android (образ без сервисов Google, как Huawei).

Управляет приложением через adb + uiautomator: тапы по тексту/описанию элементов, ввод текста.
Backend — ci/mock_backend.py (настоящий FastAPI, подменён только Claude).

Каждая проверка печатает строку RESULT|<название>|PASS/FAIL/INFO|<подробности>.
Скриншоты сохраняются в ci-out/ на каждом шаге.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "ru.voiceassistant"
APK = sys.argv[1] if len(sys.argv) > 1 else "android/app/build/outputs/apk/debug/app-debug.apk"
OUT = "ci-out"
os.makedirs(OUT, exist_ok=True)
results = []
step_no = 0


def sh(cmd, check=False, timeout=60):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"{cmd}: {p.stdout}{p.stderr}")
    return p.stdout + p.stderr


def adb(cmd, **kw):
    return sh(f"adb {cmd}", **kw)


def record(name, ok, detail=""):
    status = ok if isinstance(ok, str) else ("PASS" if ok else "FAIL")
    detail = " ".join(str(detail).split())[:220]
    results.append((name, status, detail))
    print(f"RESULT|{name}|{status}|{detail}", flush=True)


def shot(label):
    global step_no
    step_no += 1
    safe = re.sub(r"[^\w-]+", "_", label)[:40]
    adb(f"exec-out screencap -p > {OUT}/{step_no:02d}_{safe}.png")


def dump():
    for _ in range(3):
        adb("shell uiautomator dump /sdcard/ui.xml")
        xml = adb("exec-out cat /sdcard/ui.xml")
        start = xml.find("<?xml")
        if start >= 0:
            try:
                return ET.fromstring(xml[start:xml.rfind(">") + 1])
            except ET.ParseError:
                pass
        time.sleep(1)
    return ET.fromstring("<hierarchy/>")


def nodes():
    return list(dump().iter("node"))


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


_screen_h = None


def screen_h():
    global _screen_h
    if _screen_h is None:
        m = re.findall(r"(\d+)x(\d+)", adb("shell wm size"))
        _screen_h = int(m[-1][1]) if m else 2400
    return _screen_h


def in_safe_area(n):
    """Элемент не под строкой состояния и не под панелью навигации."""
    _, y = center(n)
    return 120 < y < screen_h() - 160


def find(text=None, desc=None, contains=False):
    for n in nodes():
        t, d = n.get("text", ""), n.get("content-desc", "")
        if text is not None and ((text in t) if contains else (t == text)):
            return n
        if desc is not None and ((desc in d) if contains else (d == desc)):
            return n
    return None


def wait_for(text=None, desc=None, contains=True, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        n = find(text=text, desc=desc, contains=contains)
        if n is not None:
            return n
        time.sleep(1)
    return None


def screen_text():
    return " | ".join(filter(None, (n.get("text") or n.get("content-desc") for n in nodes())))


def tap_node(n):
    x, y = center(n)
    adb(f"shell input tap {x} {y}")
    time.sleep(1)


def tap(text=None, desc=None, contains=False, timeout=15, scroll=False):
    end = time.time() + timeout
    while time.time() < end:
        n = find(text=text, desc=desc, contains=contains)
        if n is not None and (in_safe_area(n) or not scroll):
            tap_node(n)
            return True
        if scroll:
            swipe_up()
        else:
            time.sleep(1)
    raise RuntimeError(f"Не найден элемент text={text!r} desc={desc!r}. На экране: {screen_text()[:600]}")


def scroll_to(text, contains=True, tries=8):
    for _ in range(tries):
        n = find(text=text, contains=contains)
        if n is not None:
            return n
        swipe_up()
    return None


def swipe_up():
    adb("shell input swipe 540 1700 540 700 300")
    time.sleep(1)


def swipe_down_to_top():
    for _ in range(6):
        adb("shell input swipe 540 600 540 1800 200")


def type_text(s):
    adb("shell input text " + s.replace(" ", "%s"))
    time.sleep(1)


def keyboard_shown():
    return "mInputShown=true" in adb("shell dumpsys input_method")


def hide_keyboard():
    # BACK при открытой клавиатуре только закрывает её и не уходит назад по навигации.
    if keyboard_shown():
        adb("shell input keyevent 4")
        time.sleep(1)


def edit_field():
    for n in nodes():
        if n.get("class") == "android.widget.EditText":
            return n
    return None


def send_command(cmd):
    field = edit_field()
    if field is None:  # поле ввода скрыто — «Написать текстом» его показывает
        tap(text="Написать текстом", contains=True)
        field = edit_field()
    if field is None:
        raise RuntimeError("Нет поля ввода команды: " + screen_text()[:300])
    tap_node(field)
    type_text(cmd)
    tap(desc="Отправить")
    hide_keyboard()


def go_tab(title):
    tap(text=title)
    time.sleep(1)


def check(name, fn):
    try:
        ok, detail = fn()
        record(name, ok, detail)
    except Exception as e:  # noqa: BLE001
        record(name, False, f"{type(e).__name__}: {e}"[:500])
    shot(name)


# ---------------------------------------------------------------- шаги

def step_install():
    out = adb(f"install -r -g {APK}", timeout=180)
    adb(f"shell pm grant {PKG} android.permission.RECORD_AUDIO")
    adb(f"shell pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    adb(f"shell appops set {PKG} SCHEDULE_EXACT_ALARM allow")
    adb("shell cmd alarm set-timezone Europe/Moscow")
    adb("logcat -c")
    return "Success" in out, out.strip()[-200:]


def step_launch():
    adb(f"shell am start -W -n {PKG}/.ui.MainActivity")
    n = wait_for(text="Голосовой ассистент", timeout=30)
    pid = adb(f"shell pidof {PKG}").strip()
    return n is not None and pid != "", f"pid={pid or 'нет'}"


def step_onboarding():
    granted = len([n for n in nodes() if n.get("content-desc") == "Разрешено"])
    btn = scroll_to("Продолжить", contains=False)
    if btn is None:
        return False, "кнопка «Продолжить» не найдена"
    _, y = center(btn)
    tap(text="Продолжить", scroll=True, timeout=30)
    n = wait_for(text="Сервер ассистента не настроен", timeout=15)
    detail = f"разрешений выдано: {granted}; кнопка на y={y} из {screen_h()}; "
    return n is not None, detail + ("главный экран открыт" if n is not None else "остались на онбординге: " + screen_text()[-200:])


def step_backend_settings():
    tap(desc="Настройки")
    wait_for(text="Сервер ассистента", timeout=10)
    tap(text="Адрес backend API", contains=True)
    type_text("http://10.0.2.2:8000")
    tap(text="Токен приложения", contains=True)
    type_text("ci-token")
    hide_keyboard()
    tap(text="Сохранить")
    saved = wait_for(text="Сохранено", timeout=10) is not None
    tap(text="OK")
    tap(text="Проверить связь")
    ok = wait_for(text="Сервер отвечает", timeout=20)
    detail = "ответ /health получен" if ok is not None else f"на экране: {screen_text()[:300]}"
    if ok is not None:
        tap(text="OK")
    return saved and ok is not None, detail


def step_recognizer_info():
    n = scroll_to("не найден сервис распознавания речи")
    return ("INFO" if n is not None else "INFO"), (
        "образ без Google: приложение показывает предупреждение об отсутствии распознавателя"
        if n is not None else "предупреждение не показано — в образе есть сервис распознавания")


def step_task():
    swipe_down_to_top()
    tap(desc="Назад")
    send_command("add task buy bread")
    ok = wait_for(text="Готово, выполнено.", timeout=30)
    task = wait_for(text="Купить хлеб", timeout=10)
    return ok is not None and task is not None, "ответ получен, задача видна в «Задачи на сегодня»" if task is not None else screen_text()[:300]


def step_expense():
    send_command("add expense coffee")
    wait_for(text="add expense coffee", timeout=30)
    go_tab("Расходы")
    n = wait_for(text="350 ₽", timeout=10)
    cat = find(text="Кафе", contains=True)
    return n is not None and cat is not None, "350 ₽, категория «Кафе»" if n is not None else screen_text()[:300]


def step_note():
    go_tab("Сегодня")
    send_command("add note")
    wait_for(text="add note", timeout=30)
    go_tab("Заметки")
    n = wait_for(text="Код домофона 1234", timeout=10)
    return n is not None, "заметка с тегом #дом" if n is not None else screen_text()[:300]


def step_reminder_create():
    go_tab("Сегодня")
    send_command("remind me")
    wait_for(text="remind me", timeout=30)
    n = scroll_to("Проверка напоминания")
    swipe_down_to_top()
    return n is not None, "напоминание в «Ближайшие напоминания»" if n is not None else screen_text()[:300]


def step_history():
    go_tab("История")
    time.sleep(2)
    a = scroll_to("add task buy bread")
    chip = scroll_to("Новая задача", contains=False)
    return a is not None and chip is not None, "запрос, ответ и действие «Новая задача» в истории"


def step_export_import():
    tap(desc="Настройки")
    scroll_to("Экспорт в JSON")
    tap(text="Экспорт в JSON")
    time.sleep(3)
    shot("saf_export")
    # DocumentsUI: кнопка сохранения (английский или русский интерфейс эмулятора)
    for label in ("SAVE", "Save", "СОХРАНИТЬ", "Сохранить"):
        n = find(text=label)
        if n is not None:
            tap_node(n)
            break
    exported = wait_for(text="Экспортировано записей", timeout=15)
    files = adb("shell ls /sdcard/Download/").split()
    backup = next((f for f in files if f.startswith("assistant-backup")), None)
    content = adb(f"shell cat /sdcard/Download/{backup}") if backup else ""
    exp_ok = exported is not None and "voice-assistant-backup" in content and "Купить хлеб" in content
    if exported is not None:
        tap(text="OK")
    if not exp_ok:
        return False, f"экспорт: диалог={exported is not None}, файл={backup}"

    tap(text="Удалить все данные", scroll=True)
    tap(text="Удалить всё")
    wait_for(text="Все локальные данные удалены", timeout=10)
    tap(text="OK")

    tap(text="Импорт", scroll=True)
    time.sleep(3)
    shot("saf_import")
    n = wait_for(text=backup.rsplit(".", 1)[0], contains=True, timeout=10) if backup else None
    if n is None:
        return False, "файл не найден в окне выбора: " + screen_text()[:300]
    tap_node(n)
    tap(text="Заменить")
    imported = wait_for(text="Импортировано", timeout=15)
    if imported is not None:
        tap(text="OK")
    swipe_down_to_top()
    tap(desc="Назад")
    go_tab("Задачи")
    tap(text="Все")
    restored = wait_for(text="Купить хлеб", timeout=10)
    return imported is not None and restored is not None, f"файл {backup}: экспорт, удаление, импорт, задача восстановлена"


def step_reminder_fired():
    # напоминание ставилось на +75 с; ждём до 3 минут
    end = time.time() + 180
    while time.time() < end:
        out = adb("shell dumpsys notification --noredact")
        if "Проверка напоминания" in out:
            return True, "уведомление показано системой"
        time.sleep(10)
    return False, "уведомление не появилось за 3 минуты"


def step_offline():
    sh("pkill -f mock_backend.py")
    time.sleep(2)
    go_tab("Сегодня")
    send_command("hello offline")
    n = wait_for(text="Нет подключения к серверу ассистента", timeout=40)
    alive = adb(f"shell pidof {PKG}").strip() != ""
    if n is not None:
        tap(text="Понятно")
    return n is not None and alive, "понятная ошибка, приложение не упало" if n is not None else screen_text()[:300]


def step_mic():
    tap(desc="Говорить с ассистентом")
    n = wait_for(text="распознавания речи", contains=True, timeout=15)
    detail = ("без распознавателя: показана понятная ошибка" if n is not None
              else "распознаватель есть, идёт запись: " + screen_text()[:150])
    if n is not None:
        tap(text="Понятно")
    else:
        tap(desc="Закончить запись")  # остановить запись, если она началась
    return "INFO", detail


def step_crashes():
    crash = adb("logcat -b crash -d").strip()
    fatal = [l for l in adb("logcat -d").splitlines() if "FATAL EXCEPTION" in l or "ANR in " + PKG in l]
    with open(f"{OUT}/logcat.txt", "w") as f:
        f.write(adb("logcat -d"))
    return not crash and not fatal, "крашей и ANR нет" if not crash and not fatal else (crash or "\n".join(fatal))[:400]


STEPS = [
    ("Установка APK", step_install),
    ("Запуск приложения", step_launch),
    ("Онбординг и разрешения", step_onboarding),
    ("Настройка backend и проверка связи", step_backend_settings),
    ("Распознаватель речи в образе без Google", step_recognizer_info),
    ("Создание задачи голосовой командой (текстом)", step_task),
    ("Расход через backend", step_expense),
    ("Заметка через backend", step_note),
    ("Создание напоминания", step_reminder_create),
    ("История диалогов", step_history),
    ("Срабатывание напоминания", step_reminder_fired),
    ("Экспорт и импорт JSON", step_export_import),
    ("Работа без backend", step_offline),
    ("Кнопка микрофона", step_mic),
    ("Отсутствие падений", step_crashes),
]

if __name__ == "__main__":
    for name, fn in STEPS:
        check(name, fn)
        if name in ("Установка APK", "Запуск приложения") and results[-1][1] == "FAIL":
            break
    failed = [r for r in results if r[1] == "FAIL"]
    with open(f"{OUT}/results.txt", "w") as f:
        for r in results:
            f.write("|".join(r) + "\n")
    sys.exit(1 if failed else 0)
