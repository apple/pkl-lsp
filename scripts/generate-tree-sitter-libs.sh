#!/usr/bin/env bash

# generate tree-sitter lib
cd tree-sitter || exit
make
rm -rf "../src/main/resources/libtree-sitter.$1"
cp "libtree-sitter.$1" ../src/main/resources
cd ..

# generate tree-sitter-pkl lib
cd tree-sitter-pkl || exit
npm install
npm run gen-lib
rm -rf "../src/main/resources/libtree-sitter-pkl.$1"
cp "pkl.$1" "../src/main/resources/libtree-sitter-pkl.$1"

# create the links for local development
cd ..
rm -rf "libtree-sitter.$1" "libtree-sitter-pkl.$1"
ln -s "src/main/resources/libtree-sitter.$1" "libtree-sitter.$1"
ln -s "src/main/resources/libtree-sitter-pkl.$1" "libtree-sitter-pkl.$1"