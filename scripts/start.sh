#!/bin/sh

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
JAR_FILE="$APP_DIR/mongodb-log-analyzer.jar"
[ -f "$JAR_FILE" ] || JAR_FILE="$APP_DIR/target/mongodb-log-analyzer.jar"

fail() {
  echo "启动失败：$1"
  echo "按回车键关闭窗口。"
  read -r _unused
  exit 1
}

command -v java >/dev/null 2>&1 || fail "未找到 Java，请安装 Java 17 或更高版本。"

JAVA_VERSION=$(java -version 2>&1 | awk -F '[\".]' '/version/ { print $2; exit }')
case "$JAVA_VERSION" in
  ''|*[!0-9]*) fail "无法识别 Java 版本。" ;;
esac
[ "$JAVA_VERSION" -ge 17 ] || fail "当前 Java 版本低于 17。"
[ -f "$JAR_FILE" ] || fail "未找到 mongodb-log-analyzer.jar，请使用发布包或先执行 mvn package。"

cd "$APP_DIR" || fail "无法进入程序目录。"
exec java -Xms128m -Xmx2g -jar "$JAR_FILE"
