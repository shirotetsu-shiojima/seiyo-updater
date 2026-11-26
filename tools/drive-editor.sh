#!/bin/bash

# スクリプトが存在するディレクトリのパスを取得
DIR="$(cd "$(dirname "$0")" && pwd)"

# Javaコマンドを実行
java -jar "$DIR/../release/seiyo-tools.jar" editor "$DIR/seiyo-catalog-generator.html"

# エラー（終了コードが0以外）が発生した場合に一時停止する
if [ $? -ne 0 ]; then
    echo ""
    read -p "エラーが発生しました。何かキーを押して終了してください..." -n 1 -s
    echo ""
fi