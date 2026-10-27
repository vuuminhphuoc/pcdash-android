@echo off
set "SCRIPT_DIR=%~dp0"
>> "%SCRIPT_DIR%fake-omp-count.txt" echo run
type "%SCRIPT_DIR%omp-usage.fixture.json"
