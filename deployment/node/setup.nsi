; Offline wrapper only; the existing PowerShell installer owns app/data/runtime.
Unicode true
ManifestDPIAware true
RequestExecutionLevel user
SetCompressor zlib
Name "GNSS 전송 시험"
Icon "program.ico"
!define MUI_ICON "program.ico"
Caption "GNSS 전송 시험 설치"
OutFile "${OUTPUT}"
VIProductVersion "1.0.0.0"
VIAddVersionKey /LANG=1042 "ProductName" "GNSS 전송 시험"
VIAddVersionKey /LANG=1042 "CompanyName" "imtplus"
VIAddVersionKey /LANG=1042 "FileDescription" "GNSS 전송 시험 오프라인 설치"
VIAddVersionKey /LANG=1042 "FileVersion" "1.0"
VIAddVersionKey /LANG=1042 "LegalCopyright" "Copyright imtplus"
BrandingText "imtplus · Release 1.0"
!include "MUI2.nsh"
!include "nsDialogs.nsh"
!include "LogicLib.nsh"
!include "x64.nsh"
!include "FileFunc.nsh"

ShowInstDetails show
!define MUI_CUSTOMFUNCTION_ABORT SetupAbort
!define MUI_WELCOMEPAGE_TITLE "GNSS 전송 시험 1.0"
!define MUI_WELCOMEPAGE_TEXT "이 PC에 송신 또는 수신 프로그램을 설치합니다.$\r$\n$\r$\n필요한 실행 환경이 포함되어 있으며 인터넷 연결은 필요하지 않습니다.$\r$\n$\r$\nWindows 구성요소 준비 중 관리자 승인과 재부팅이 필요할 수 있습니다."
!insertmacro MUI_PAGE_WELCOME
Page custom RolePage RoleLeave
Page custom AddressPage AddressLeave
Page custom AuthPage AuthLeave
Page custom AdvancedPage AdvancedLeave
Page custom ReviewPage ReviewLeave
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_TITLE "설치 완료"
!define MUI_FINISHPAGE_TEXT "바탕화면의 송신 또는 수신 바로가기로 실행할 수 있습니다.$\r$\n$\r$\n상대 PC와 어댑터 주소는 시험 화면에서 설정할 수 있습니다."
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "시험 화면 열기"
!define MUI_FINISHPAGE_RUN_FUNCTION OpenProgram
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_LANGUAGE "Korean"

Var Dialog
Var Role
Var Destination
Var Existing
Var RoleSender
Var RoleReceiver
Var DirectoryEdit
Var Advanced
Var AdvancedCheck
Var Hints
Var Request
Var Settings
Var LocalIp
Var LocalPort
Var PeerIp
Var PeerPort
Var AdapterIp
Var AdapterPort
Var LocalEdit
Var PortEdit
Var PeerEdit
Var PeerPortEdit
Var AdapterEdit
Var AdapterPortEdit
Var ManagementToken
Var SendToken
Var ReceiveToken
Var ManagementEdit
Var AdapterTokenEdit
Var RevealCheck
Var IqPath
Var Ntp
Var IqEdit
Var NtpEdit
Var Installing
Var InstallLog

!macro Label X Y W Text
  ${NSD_CreateLabel} ${X} ${Y} ${W} 12u "${Text}"
  Pop $0
  ${NSD_RemoveExStyle} $0 ${WS_EX_TRANSPARENT}
!macroend
!macro ReadHint Variable Section Key
  ReadINIStr $${Variable} $Hints "${Section}" "${Key}"
!macroend
!macro WriteValue Key Value
  WriteINIStr $Request "env" "${Key}" "${Value}"
!macroend

Function .onInit
  SetShellVarContext current
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "Windows 11 x64 PC에서 실행하세요."
    Abort
  ${EndIf}
  ReadRegStr $0 HKLM "SOFTWARE\Microsoft\Windows NT\CurrentVersion" "CurrentBuildNumber"
  ${If} $0 < 22000
    MessageBox MB_ICONSTOP "Windows 11 이상이 필요합니다."
    Abort
  ${EndIf}
  InitPluginsDir
  StrCpy $Hints "$PLUGINSDIR\hints.ini"
  StrCpy $Request "$PLUGINSDIR\request.ini"
  StrCpy $Settings "$PLUGINSDIR\configuration.env"
  StrCpy $InstallLog "$LOCALAPPDATA\imtplus\Setup\install.log"
  SetOutPath "$PLUGINSDIR\bundle\installer"
  File "${BUNDLE}\installer\setup-settings.ps1"
  SetOutPath "$PLUGINSDIR\bundle\app"
  File "${BUNDLE}\app\node-runtime.ps1"
  File "${BUNDLE}\app\.env.example"
  nsExec::ExecToStack '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$PLUGINSDIR\bundle\installer\setup-settings.ps1" -Action Inspect -Hints "$Hints"'
  Pop $0
  Pop $1
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "설치 설정을 확인하지 못했습니다.$\r$\n$1"
    Abort
  ${EndIf}
  !insertmacro ReadHint Destination setup destination
  Call LoadHints
  FileOpen $0 $Request w
  FileWriteUTF16LE /BOM $0 ""
  FileClose $0
FunctionEnd

Function LoadHints
  !insertmacro ReadHint Existing setup existing
  !insertmacro ReadHint Role env LNIS_NODE_ROLE
  !insertmacro ReadHint LocalIp network localIp
  !insertmacro ReadHint LocalPort env LNIS_SERVER_PORT
  !insertmacro ReadHint PeerIp network peerIp
  !insertmacro ReadHint PeerPort network peerPort
  !insertmacro ReadHint AdapterIp network adapterIp
  !insertmacro ReadHint AdapterPort network adapterPort
  !insertmacro ReadHint ManagementToken env LNIS_NODE_MANAGEMENT_TOKEN
  !insertmacro ReadHint SendToken env LNIS_DTN_SEND_TOKEN
  !insertmacro ReadHint ReceiveToken env LNIS_DTN_RECEIVE_TOKEN
  !insertmacro ReadHint IqPath env LNIS_IQ_HOST_PATH
  !insertmacro ReadHint Ntp env LNIS_NTP_SERVER
FunctionEnd

Function RolePage
  !insertmacro MUI_HEADER_TEXT "설치 위치와 역할" "이 PC에서 사용할 역할을 선택하세요."
  nsDialogs::Create 1018
  Pop $Dialog
  ${NSD_CreateRadioButton} 0 5u 130u 14u "송신 PC"
  Pop $RoleSender
  ${NSD_CreateRadioButton} 140u 5u 130u 14u "수신 PC"
  Pop $RoleReceiver
  ${If} $Role == receiver
    ${NSD_Check} $RoleReceiver
  ${Else}
    ${NSD_Check} $RoleSender
  ${EndIf}
  !insertmacro Label 0 36u 100% "설치 위치"
  ${NSD_CreateDirRequest} 0 53u 250u 14u "$Destination"
  Pop $DirectoryEdit
  ${NSD_CreateBrowseButton} 256u 53u 44u 14u "찾기"
  Pop $0
  ${NSD_OnClick} $0 BrowseDirectory
  ${NSD_CreateCheckbox} 0 82u 100% 14u "고급 설정 확인 (I/Q 폴더 · 시간 서버)"
  Pop $AdvancedCheck
  ${If} $Advanced == 1
    ${NSD_Check} $AdvancedCheck
  ${EndIf}
  !insertmacro Label 0 110u 100% "기존 설치 경로를 선택하면 설정과 시험 자료를 유지합니다."
  nsDialogs::Show
FunctionEnd

Function BrowseDirectory
  Pop $0
  nsDialogs::SelectFolderDialog "설치 위치" "$Destination"
  Pop $0
  ${If} $0 != error
    ${NSD_SetText} $DirectoryEdit $0
  ${EndIf}
FunctionEnd

Function RoleLeave
  ${NSD_GetText} $DirectoryEdit $0
  ${NSD_GetState} $AdvancedCheck $Advanced
  ${If} $0 != $Destination
    StrCpy $Destination $0
    nsExec::ExecToStack '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$PLUGINSDIR\bundle\installer\setup-settings.ps1" -Action Inspect -Destination "$Destination" -Hints "$Hints"'
    Pop $0
    Pop $1
    ${If} $0 != 0
      MessageBox MB_ICONSTOP "$1"
      Abort
    ${EndIf}
    Call LoadHints
  ${EndIf}
  ${NSD_GetState} $RoleReceiver $0
  StrCpy $1 sender
  ${If} $0 == 1
    StrCpy $1 receiver
  ${EndIf}
  ${If} $Existing == 1
  ${AndIf} $1 != $Role
    MessageBox MB_ICONEXCLAMATION "기존 설치의 역할은 변경할 수 없습니다. 다른 설치 위치를 선택하세요."
    Abort
  ${EndIf}
  StrCpy $Role $1
FunctionEnd

Function AddressPage
  !insertmacro MUI_HEADER_TEXT "서비스 연결" "내 PC 주소는 자동으로 입력합니다. 상대 주소는 나중에 설정할 수 있습니다."
  nsDialogs::Create 1018
  Pop $Dialog
  !insertmacro Label 0 0 235u "내 PC 주소"
  !insertmacro Label 246u 0 54u "포트"
  ${NSD_CreateComboBox} 0 16u 235u 90u "$LocalIp"
  Pop $LocalEdit
  ReadINIStr $1 $Hints setup ipCount
  StrCpy $2 0
  ${While} $2 < $1
    ReadINIStr $0 $Hints setup "ip$2"
    ${NSD_CB_AddString} $LocalEdit $0
    IntOp $2 $2 + 1
  ${EndWhile}
  ${NSD_SetText} $LocalEdit "$LocalIp"
  ${NSD_CreateNumber} 246u 16u 54u 14u "$LocalPort"
  Pop $PortEdit
  !insertmacro Label 0 46u 235u "상대 PC 주소 (선택)"
  ${NSD_CreateText} 0 62u 235u 14u "$PeerIp"
  Pop $PeerEdit
  ${NSD_Edit_SetCueBannerText} $PeerEdit 0 "예: 192.168.1.73"
  ${NSD_CreateNumber} 246u 62u 54u 14u "$PeerPort"
  Pop $PeerPortEdit
  !insertmacro Label 0 92u 235u "이 PC와 연결할 어댑터 주소 (선택)"
  ${NSD_CreateText} 0 108u 235u 14u "$AdapterIp"
  Pop $AdapterEdit
  ${NSD_Edit_SetCueBannerText} $AdapterEdit 0 "예: 192.168.1.154"
  ${NSD_CreateNumber} 246u 108u 54u 14u "$AdapterPort"
  Pop $AdapterPortEdit
  nsDialogs::Show
FunctionEnd

Function AddressLeave
  ${NSD_GetText} $LocalEdit $LocalIp
  ${NSD_GetText} $PortEdit $LocalPort
  ${NSD_GetText} $PeerEdit $PeerIp
  ${NSD_GetText} $PeerPortEdit $PeerPort
  ${NSD_GetText} $AdapterEdit $AdapterIp
  ${NSD_GetText} $AdapterPortEdit $AdapterPort
FunctionEnd

Function AuthPage
  !insertmacro MUI_HEADER_TEXT "연결 인증값" "기존 서비스와 어댑터 연결에 사용하는 값입니다."
  nsDialogs::Create 1018
  Pop $Dialog
  !insertmacro Label 0 0 100% "서비스 연결 인증값 · 상대 PC와 동일하게 사용"
  ${NSD_CreatePassword} 0 18u 300u 14u "$ManagementToken"
  Pop $ManagementEdit
  !insertmacro Label 0 39u 100% "새 설치는 자동 생성됩니다. 기존 상대 PC가 있으면 같은 값을 입력하세요."
  ${If} $Role == receiver
    !insertmacro Label 0 66u 100% "수신 어댑터 인증값 · 어댑터의 수신 API 설정과 일치"
    ${NSD_CreatePassword} 0 84u 300u 14u "$ReceiveToken"
  ${Else}
    !insertmacro Label 0 66u 100% "송신 어댑터 인증값 · 인증을 사용하지 않으면 비워 두세요."
    ${NSD_CreatePassword} 0 84u 300u 14u "$SendToken"
  ${EndIf}
  Pop $AdapterTokenEdit
  ${NSD_CreateCheckbox} 0 112u 100% 14u "인증값 표시"
  Pop $RevealCheck
  ${NSD_OnClick} $RevealCheck RevealTokens
  nsDialogs::Show
FunctionEnd

Function RevealTokens
  Pop $0
  ${NSD_GetState} $RevealCheck $0
  StrCpy $1 9679
  ${If} $0 == 1
    StrCpy $1 0
  ${EndIf}
  SendMessage $ManagementEdit ${EM_SETPASSWORDCHAR} $1 0
  SendMessage $AdapterTokenEdit ${EM_SETPASSWORDCHAR} $1 0
  System::Call 'user32::InvalidateRect(p $ManagementEdit, p 0, i 1)'
  System::Call 'user32::InvalidateRect(p $AdapterTokenEdit, p 0, i 1)'
FunctionEnd

Function AuthLeave
  ${NSD_GetText} $ManagementEdit $ManagementToken
  ${If} $Role == receiver
    ${NSD_GetText} $AdapterTokenEdit $ReceiveToken
  ${Else}
    ${NSD_GetText} $AdapterTokenEdit $SendToken
  ${EndIf}
FunctionEnd

Function AdvancedPage
  ${If} $Advanced != 1
    Abort
  ${EndIf}
  !insertmacro MUI_HEADER_TEXT "고급 설정" "일반 설치는 기본값을 그대로 사용하세요."
  nsDialogs::Create 1018
  Pop $Dialog
  !insertmacro Label 0 0 100% "I/Q 파일 경로 · 어댑터 기준"
  ${NSD_CreateDirRequest} 0 18u 300u 14u "$IqPath"
  Pop $IqEdit
  !insertmacro Label 0 44u 100% "실제 파일은 data\exchange에 저장됩니다. 어댑터 기준 경로가 다를 때만 변경하세요."
  !insertmacro Label 0 72u 100% "내부 NTP 서버 (선택)"
  ${NSD_CreateText} 0 90u 300u 14u "$Ntp"
  Pop $NtpEdit
  nsDialogs::Show
FunctionEnd

Function AdvancedLeave
  ${NSD_GetText} $IqEdit $IqPath
  ${NSD_GetText} $NtpEdit $Ntp
FunctionEnd

Function SaveRequest
  WriteINIStr $Request setup destination "$Destination"
  !insertmacro WriteValue LNIS_NODE_ROLE "$Role"
  !insertmacro WriteValue LNIS_SERVER_PORT "$LocalPort"
  !insertmacro WriteValue LNIS_NODE_BASE_URL "http://$LocalIp:$LocalPort"
  StrCpy $0 ""
  ${If} $PeerIp != ""
    StrCpy $0 "http://$PeerIp:$PeerPort"
  ${EndIf}
  !insertmacro WriteValue LNIS_NODE_PEER_URL "$0"
  StrCpy $0 ""
  ${If} $AdapterIp != ""
    StrCpy $0 "http://$AdapterIp:$AdapterPort"
  ${EndIf}
  !insertmacro WriteValue dtn_adapter "$0"
  !insertmacro WriteValue LNIS_NODE_MANAGEMENT_TOKEN "$ManagementToken"
  !insertmacro WriteValue LNIS_DTN_SEND_TOKEN "$SendToken"
  !insertmacro WriteValue LNIS_DTN_RECEIVE_TOKEN "$ReceiveToken"
  !insertmacro WriteValue LNIS_IQ_HOST_PATH "$IqPath"
  !insertmacro WriteValue LNIS_NTP_SERVER "$Ntp"
FunctionEnd

Function ReviewPage
  Call SaveRequest
  !insertmacro MUI_HEADER_TEXT "설정 확인" "확인 후 설치를 누르세요."
  nsDialogs::Create 1018
  Pop $Dialog
  StrCpy $0 송신
  ${If} $Role == receiver
    StrCpy $0 수신
  ${EndIf}
  !insertmacro Label 0 0 100% "역할: $0 PC"
  !insertmacro Label 0 22u 100% "설치 위치: $Destination"
  !insertmacro Label 0 44u 100% "내 PC: http://$LocalIp:$LocalPort"
  StrCpy $0 "나중에 설정"
  ${If} $PeerIp != ""
    StrCpy $0 "$PeerIp:$PeerPort"
  ${EndIf}
  !insertmacro Label 0 66u 100% "상대 PC: $0"
  StrCpy $0 "나중에 설정"
  ${If} $AdapterIp != ""
    StrCpy $0 "$AdapterIp:$AdapterPort"
  ${EndIf}
  !insertmacro Label 0 88u 100% "어댑터: $0"
  !insertmacro Label 0 116u 100% "서비스·Docker·Windows 접속 포트는 자동으로 일치시킵니다."
  nsDialogs::Show
FunctionEnd

Function ReviewLeave
  Call SaveRequest
  nsExec::ExecToStack '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$PLUGINSDIR\bundle\installer\setup-settings.ps1" -Action Prepare -Request "$Request" -Configuration "$Settings"'
  Pop $0
  Pop $1
  ${If} $0 != 0
    MessageBox MB_ICONEXCLAMATION "입력 내용을 확인하세요.$\r$\n$1"
    Abort
  ${EndIf}
FunctionEnd

Section "설치"
  StrCpy $Installing 1
  GetDlgItem $0 $HWNDPARENT 2
  EnableWindow $0 0
  SetDetailsPrint textonly
  SetOutPath "$PLUGINSDIR\bundle"
  File /r "${BUNDLE}\*"
  SetDetailsPrint both
  CreateDirectory "$LOCALAPPDATA\imtplus\Setup"
  ${NSD_AddStyle} $mui.InstFilesPage.ProgressBar 0x8
  SendMessage $mui.InstFilesPage.ProgressBar 0x40A 1 80
  DetailPrint "실행 환경과 서비스를 설치합니다."
  nsExec::ExecToLog '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$PLUGINSDIR\bundle\installer\install.ps1" -BundleRoot "$PLUGINSDIR\bundle" -Destination "$Destination" -Configuration "$Settings" -LogFile "$InstallLog" -NoBrowser'
  Pop $0
  SendMessage $mui.InstFilesPage.ProgressBar 0x40A 0 0
  StrCpy $Installing 0
  ${If} $0 == 3010
    SetErrorLevel 3010
    MessageBox MB_ICONINFORMATION "Windows 구성요소를 준비했습니다.$\r$\nPC를 재부팅한 뒤 같은 Setup.exe를 다시 실행하세요.$\r$\n입력한 설정은 보존됩니다."
    Quit
  ${EndIf}
  ${If} $0 != 0
    SetErrorLevel 1
    MessageBox MB_ICONSTOP "설치를 완료하지 못했습니다.$\r$\n원인을 확인한 뒤 Setup.exe를 다시 실행하세요.$\r$\n$\r$\n설치 기록: $InstallLog"
    Abort
  ${EndIf}
  nsExec::ExecToStack '"$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "$PLUGINSDIR\bundle\installer\setup-settings.ps1" -Action Clear'
  Pop $0
  Pop $1
  Delete $Settings
  Delete $Request
SectionEnd

Function OpenProgram
  ExecShell "open" "http://$LocalIp:$LocalPort"
FunctionEnd

Function SetupAbort
  ${If} $Installing == 1
    MessageBox MB_ICONINFORMATION "설치 중입니다. 현재 작업이 끝난 뒤 닫을 수 있습니다."
    Abort
  ${EndIf}
FunctionEnd
