@ECHO OFF
IF EXIST java (
	start "NAS NRS" java -cp nxt.jar;lib\*;conf nxt.Nxt
) ELSE (
	IF EXIST "%PROGRAMFILES%\Java\jre7" (
		start "NAS NRS" "%PROGRAMFILES%\Java\jre7\bin\java.exe" -cp nxt.jar;lib\*;conf nxt.Nxt
	) ELSE (
		IF EXIST "%PROGRAMFILES(X86)%\Java\jre7" (
			start "NAS NRS" "%PROGRAMFILES(X86)%\Java\jre7\bin\java.exe" -cp nxt.jar;lib\*;conf nxt.Nxt
		) ELSE (
			ECHO Java software not found on your system. Please go to http://java.com/en/ to download a copy of Java.
			PAUSE
		)
	)
)