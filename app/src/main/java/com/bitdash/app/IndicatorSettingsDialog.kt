package com.bitdash.app

import android.app.Activity
import android.app.AlertDialog
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.bitdash.app.market.Prefs

/**
 * 技术指标参数设置弹窗 (MA, BOLL, MACD, RSI, KDJ)
 */
object IndicatorSettingsDialog {

    fun show(activity: Activity, onSaved: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_indicator_settings, null)

        val etMa1 = view.findViewById<EditText>(R.id.etMa1)
        val etMa2 = view.findViewById<EditText>(R.id.etMa2)
        val etMa3 = view.findViewById<EditText>(R.id.etMa3)

        val etEma1 = view.findViewById<EditText>(R.id.etEma1)
        val etEma2 = view.findViewById<EditText>(R.id.etEma2)
        val etEma3 = view.findViewById<EditText>(R.id.etEma3)

        val etBollN = view.findViewById<EditText>(R.id.etBollN)
        val etBollK = view.findViewById<EditText>(R.id.etBollK)

        val etSuperTrendAtr = view.findViewById<EditText>(R.id.etSuperTrendAtr)
        val etSuperTrendFactor = view.findViewById<EditText>(R.id.etSuperTrendFactor)

        val etTurtleEntry = view.findViewById<EditText>(R.id.etTurtleEntry)
        val etTurtleExit = view.findViewById<EditText>(R.id.etTurtleExit)
        val etTurtleAtr = view.findViewById<EditText>(R.id.etTurtleAtr)

        val etMacdFast = view.findViewById<EditText>(R.id.etMacdFast)
        val etMacdSlow = view.findViewById<EditText>(R.id.etMacdSlow)
        val etMacdSig = view.findViewById<EditText>(R.id.etMacdSig)

        val etRsi1 = view.findViewById<EditText>(R.id.etRsi1)
        val etRsi2 = view.findViewById<EditText>(R.id.etRsi2)
        val etRsi3 = view.findViewById<EditText>(R.id.etRsi3)

        val etKdjN = view.findViewById<EditText>(R.id.etKdjN)
        val etKdjM1 = view.findViewById<EditText>(R.id.etKdjM1)
        val etKdjM2 = view.findViewById<EditText>(R.id.etKdjM2)

        fun loadCurrentValues() {
            etMa1.setText(Prefs.getMa1Period(activity).toString())
            etMa2.setText(Prefs.getMa2Period(activity).toString())
            etMa3.setText(Prefs.getMa3Period(activity).toString())

            etEma1.setText(Prefs.getEma1Period(activity).toString())
            etEma2.setText(Prefs.getEma2Period(activity).toString())
            etEma3.setText(Prefs.getEma3Period(activity).toString())

            etBollN.setText(Prefs.getBollN(activity).toString())
            etBollK.setText(Prefs.getBollK(activity).toString())

            etSuperTrendAtr.setText(Prefs.getSuperTrendAtr(activity).toString())
            etSuperTrendFactor.setText(Prefs.getSuperTrendFactor(activity).toString())

            etTurtleEntry.setText(Prefs.getTurtleEntry(activity).toString())
            etTurtleExit.setText(Prefs.getTurtleExit(activity).toString())
            etTurtleAtr.setText(Prefs.getTurtleAtr(activity).toString())

            etMacdFast.setText(Prefs.getMacdFast(activity).toString())
            etMacdSlow.setText(Prefs.getMacdSlow(activity).toString())
            etMacdSig.setText(Prefs.getMacdSignal(activity).toString())

            etRsi1.setText(Prefs.getRsi1Period(activity).toString())
            etRsi2.setText(Prefs.getRsi2Period(activity).toString())
            etRsi3.setText(Prefs.getRsi3Period(activity).toString())

            etKdjN.setText(Prefs.getKdjN(activity).toString())
            etKdjM1.setText(Prefs.getKdjM1(activity).toString())
            etKdjM2.setText(Prefs.getKdjM2(activity).toString())
        }

        loadCurrentValues()

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        view.findViewById<Button>(R.id.btnResetDefault).setOnClickListener {
            Prefs.resetIndicatorParams(activity)
            loadCurrentValues()
            Toast.makeText(activity, "已恢复默认指标参数", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val ma1 = etMa1.text.toString().toIntOrNull() ?: Prefs.DEFAULT_MA1
            val ma2 = etMa2.text.toString().toIntOrNull() ?: Prefs.DEFAULT_MA2
            val ma3 = etMa3.text.toString().toIntOrNull() ?: Prefs.DEFAULT_MA3

            val ema1 = etEma1.text.toString().toIntOrNull() ?: Prefs.DEFAULT_EMA1
            val ema2 = etEma2.text.toString().toIntOrNull() ?: Prefs.DEFAULT_EMA2
            val ema3 = etEma3.text.toString().toIntOrNull() ?: Prefs.DEFAULT_EMA3

            val bollN = etBollN.text.toString().toIntOrNull() ?: Prefs.DEFAULT_BOLL_N
            val bollK = etBollK.text.toString().toFloatOrNull() ?: Prefs.DEFAULT_BOLL_K

            val stAtr = etSuperTrendAtr.text.toString().toIntOrNull() ?: Prefs.DEFAULT_SUPERTREND_ATR
            val stFactor = etSuperTrendFactor.text.toString().toFloatOrNull() ?: Prefs.DEFAULT_SUPERTREND_FACTOR

            val turtleEntry = etTurtleEntry.text.toString().toIntOrNull() ?: Prefs.DEFAULT_TURTLE_ENTRY
            val turtleExit = etTurtleExit.text.toString().toIntOrNull() ?: Prefs.DEFAULT_TURTLE_EXIT
            val turtleAtr = etTurtleAtr.text.toString().toIntOrNull() ?: Prefs.DEFAULT_TURTLE_ATR

            val macdFast = etMacdFast.text.toString().toIntOrNull() ?: Prefs.DEFAULT_MACD_FAST
            val macdSlow = etMacdSlow.text.toString().toIntOrNull() ?: Prefs.DEFAULT_MACD_SLOW
            val macdSig = etMacdSig.text.toString().toIntOrNull() ?: Prefs.DEFAULT_MACD_SIGNAL

            val rsi1 = etRsi1.text.toString().toIntOrNull() ?: Prefs.DEFAULT_RSI1
            val rsi2 = etRsi2.text.toString().toIntOrNull() ?: Prefs.DEFAULT_RSI2
            val rsi3 = etRsi3.text.toString().toIntOrNull() ?: Prefs.DEFAULT_RSI3

            val kdjN = etKdjN.text.toString().toIntOrNull() ?: Prefs.DEFAULT_KDJ_N
            val kdjM1 = etKdjM1.text.toString().toIntOrNull() ?: Prefs.DEFAULT_KDJ_M1
            val kdjM2 = etKdjM2.text.toString().toIntOrNull() ?: Prefs.DEFAULT_KDJ_M2

            Prefs.saveIndicatorParams(
                activity,
                ma1, ma2, ma3,
                ema1, ema2, ema3,
                bollN, bollK,
                stAtr, stFactor,
                turtleEntry, turtleExit, turtleAtr,
                macdFast, macdSlow, macdSig,
                rsi1, rsi2, rsi3,
                kdjN, kdjM1, kdjM2
            )
            dialog.dismiss()
            onSaved()
            Toast.makeText(activity, "指标参数已保存", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }
}
