cmake_minimum_required(VERSION 3.19.2)

project("lite-unzip")

add_library(lite-unzip STATIC ioapi.c liteunz.c unzip.c)
