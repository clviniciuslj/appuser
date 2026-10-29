package br.com.pingreal

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

/**
 * Janelinha para digitar o IP do widget. O Android não permite campo de texto dentro de widget da tela
 * inicial, então tocar no campo do widget abre esta janela sobre a tela inicial.
 */
class WidgetIpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "172.16.1.1"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(Prefs.of(this@WidgetIpActivity).getString(Prefs.WIDGET_HOST, ""))
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            val pad = (22 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("IP de destino")
            .setView(box)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancelar") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        // Em endereço inválido a janela fica aberta, em vez de fechar e perder o que foi digitado.
        fun save() {
            val host = parseHost(input.text.toString().trim())
            if (host == null) {
                Toast.makeText(this, "Endereço inválido", Toast.LENGTH_SHORT).show()
                return
            }
            Prefs.of(this).edit().putString(Prefs.WIDGET_HOST, host).apply()
            PingWidgetProvider.refresh(this)
            dialog.dismiss()
            finish()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save()
                true
            } else {
                false
            }
        }
    }
}
