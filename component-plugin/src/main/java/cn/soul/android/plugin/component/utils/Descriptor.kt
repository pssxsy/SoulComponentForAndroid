package cn.soul.android.plugin.component.utils

/**
 * @author panxinghai
 *
 * date : 2019-09-27 23:13
 */
class Descriptor {
    companion object {
        fun getTaskNameWithoutModule(name: String): String {
            return name.substring(name.lastIndexOf(':') + 1)
        }

        fun getTaskModuleName(name: String): String {
            val str = name.substring(0, name.lastIndexOf(':'))
            if (str[0] == ':') {
                return str.substring(1, str.length)
            }
            return str
        }
    }
}