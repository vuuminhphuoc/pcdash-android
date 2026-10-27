@echo off
rem Shows the setup QR code (IP, MAC, port, token) for the PCDash host phone to scan.
start "" "%LOCALAPPDATA%\PcAgent\PcAgent.exe" --setup
