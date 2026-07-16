package com.hidsquid.rootrecoveryhelper.autorecovery

object AutoRecoveryScript {
    fun build(modules: List<RequiredModule> = RequiredModules.all): String {
        val moduleCommands = modules.joinToString(separator = "\n") { module ->
            "process_module \"${module.id}\" \"${module.directory}\""
        }

        return """
            root_uid="${'$'}(id -u 2>/dev/null)"
            if [ "${'$'}root_uid" != "0" ]; then
                echo "RRH|ROOT|DENIED"
                exit 20
            fi
            echo "RRH|ROOT|OK"

            settings put global adb_enabled 1
            adb_settings_exit=${'$'}?
            setprop ctl.start adbd
            adb_daemon_exit=${'$'}?
            echo "RRH|ADB|${'$'}adb_settings_exit|${'$'}adb_daemon_exit"

            deleted_count=0
            failure_count=0
            zygisk_ready=0
            zygisk_changed=0

            process_module() {
                module_id="${'$'}1"
                module_dir="${'$'}2"
                disable_file="${'$'}module_dir/disable"
                echo "RRH|MODULE_START|${'$'}module_id"

                dir_output="${'$'}(/system/bin/ls -d "${'$'}module_dir" 2>&1)"
                dir_exit=${'$'}?
                if [ "${'$'}dir_exit" -ne 0 ]; then
                    case "${'$'}dir_output" in
                        *"No such file"*)
                            echo "RRH|MODULE_RESULT|${'$'}module_id|MISSING"
                            ;;
                        *)
                            failure_count=${'$'}((failure_count + 1))
                            echo "RRH|MODULE_RESULT|${'$'}module_id|UNKNOWN"
                            ;;
                    esac
                    return
                fi

                disable_output="${'$'}(/system/bin/ls -d "${'$'}disable_file" 2>&1)"
                disable_exit=${'$'}?
                if [ "${'$'}disable_exit" -ne 0 ]; then
                    case "${'$'}disable_output" in
                        *"No such file"*)
                            echo "RRH|MODULE_RESULT|${'$'}module_id|ABSENT"
                            ;;
                        *)
                            failure_count=${'$'}((failure_count + 1))
                            echo "RRH|MODULE_RESULT|${'$'}module_id|UNKNOWN"
                            ;;
                    esac
                    return
                fi

                /system/bin/rm -f "${'$'}disable_file"
                remove_exit=${'$'}?
                if [ "${'$'}remove_exit" -ne 0 ]; then
                    failure_count=${'$'}((failure_count + 1))
                    echo "RRH|MODULE_RESULT|${'$'}module_id|FAILED"
                    return
                fi

                verify_output="${'$'}(/system/bin/ls -d "${'$'}disable_file" 2>&1)"
                verify_exit=${'$'}?
                if [ "${'$'}verify_exit" -eq 0 ]; then
                    failure_count=${'$'}((failure_count + 1))
                    echo "RRH|MODULE_RESULT|${'$'}module_id|FAILED"
                else
                    case "${'$'}verify_output" in
                        *"No such file"*)
                            deleted_count=${'$'}((deleted_count + 1))
                            echo "RRH|MODULE_RESULT|${'$'}module_id|DELETED"
                            ;;
                        *)
                            failure_count=${'$'}((failure_count + 1))
                            echo "RRH|MODULE_RESULT|${'$'}module_id|UNKNOWN"
                            ;;
                    esac
                fi
            }

            $moduleCommands

            is_zygisk_enabled() {
                case "${'$'}1" in
                    "1"|*"zygisk_enabled=1"*) return 0 ;;
                    *) return 1 ;;
                esac
            }

            read_zygisk_state() {
                magisk --sqlite "SELECT CASE WHEN EXISTS(SELECT 1 FROM settings WHERE key='zygisk' AND value=1) THEN 1 ELSE 0 END AS zygisk_enabled;" 2>&1
            }

            echo "RRH|ZYGISK|ENABLING"
            zygisk_before="${'$'}(read_zygisk_state)"
            zygisk_query_exit=${'$'}?
            if [ "${'$'}zygisk_query_exit" -ne 0 ]; then
                failure_count=${'$'}((failure_count + 1))
                echo "RRH|ZYGISK|UNSUPPORTED"
            elif is_zygisk_enabled "${'$'}zygisk_before"; then
                zygisk_ready=1
                echo "RRH|ZYGISK|ALREADY_ENABLED"
            else
                magisk --sqlite "INSERT OR REPLACE INTO settings (key, value) VALUES ('zygisk', 1);" >/dev/null 2>&1
                zygisk_write_exit=${'$'}?
                if [ "${'$'}zygisk_write_exit" -ne 0 ]; then
                    failure_count=${'$'}((failure_count + 1))
                    echo "RRH|ZYGISK|FAILED"
                else
                    zygisk_after="${'$'}(read_zygisk_state)"
                    zygisk_verify_exit=${'$'}?
                    if [ "${'$'}zygisk_verify_exit" -eq 0 ] && is_zygisk_enabled "${'$'}zygisk_after"; then
                        zygisk_ready=1
                        zygisk_changed=1
                        echo "RRH|ZYGISK|ENABLED"
                    else
                        failure_count=${'$'}((failure_count + 1))
                        echo "RRH|ZYGISK|FAILED"
                    fi
                fi
            fi

            recovery_changed=0
            if [ "${'$'}deleted_count" -gt 0 ] || [ "${'$'}zygisk_changed" -eq 1 ]; then
                recovery_changed=1
            fi

            echo "RRH|SUMMARY|${'$'}deleted_count|${'$'}failure_count|${'$'}zygisk_ready|${'$'}zygisk_changed"
            if [ "${'$'}recovery_changed" -eq 1 ] && [ "${'$'}failure_count" -eq 0 ] && [ "${'$'}zygisk_ready" -eq 1 ]; then
                seconds=10
                while [ "${'$'}seconds" -gt 0 ]; do
                    echo "RRH|COUNTDOWN|${'$'}seconds"
                    sleep 1
                    seconds=${'$'}((seconds - 1))
                done
                echo "RRH|REBOOT|REQUESTED"
                reboot
                reboot_exit=${'$'}?
                echo "RRH|REBOOT|FAILED_${'$'}reboot_exit"
                exit "${'$'}reboot_exit"
            fi

            echo "RRH|REBOOT|SKIPPED"
            exit 0
        """.trimIndent()
    }
}
