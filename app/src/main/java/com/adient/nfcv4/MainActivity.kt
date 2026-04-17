package com.adient.nfcv4

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.Observer
import com.canhub.cropper.CropImage
import com.canhub.cropper.CropImageView

class MainActivity : AppCompatActivity() {
    private var mPreferencesController: Preferences? = null
    private var mHasReFlashableImage: Boolean = false
    private val mReFlashButton: CardView get() = findViewById(R.id.reflashButton)
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Register action bar / toolbar
        setSupportActionBar(findViewById(R.id.main_toolbar))

        // Get user preferences
        this.mPreferencesController = Preferences(this)
        this.updateScreenSizeDisplay(null)

        setupClickListeners()
        setupProtocolSpinner()
        checkReFlashAbility()
        observeViewModel()

        val savedUrl = mPreferencesController?.getPreferences()?.getString(PrefKeys.BaseUrl, "")
        if (!savedUrl.isNullOrEmpty()) {
            viewModel.setBaseUrl(savedUrl)
            // We fetch the stations when the user goes to the TextEditor screen, not on startup.
        }
    }

    private fun observeViewModel() {
        val reFlashImagePreview: ImageView = findViewById(R.id.reflashButtonImage)
        val progressBar: ProgressBar = findViewById(R.id.progress_bar)

        // This observer will update the main image preview when an image is downloaded from the TextEditor screen
        viewModel.isLoading.observe(this, Observer { isLoading ->
            progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        })

        viewModel.errorMessage.observe(this, Observer { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun setupClickListeners() {
        val imageFilePickerCTA: Button = findViewById(R.id.cta_pick_image_file)
        val textEditButtonInvite: Button = findViewById(R.id.cta_new_text)
        val testDitheringButton: Button = findViewById(R.id.btn_test_dithering)
        val screenSizeChangeInvite: Button = findViewById(R.id.changeDisplaySizeInvite)
        val settingsButton: Button = findViewById(R.id.newTopRightButton)
        val settingsLayout: ConstraintLayout = findViewById(R.id.settings_screen_layout)
        val saveUrlButton: Button = findViewById(R.id.save_url_button)

        // This button now correctly opens the TextEditor screen
        textEditButtonInvite.setOnClickListener {
            val intent = Intent(this, TextEditor::class.java)
            startActivity(intent)
        }

        // Generate a black-to-white horizontal gradient and flash it.
        // The dithering test: with Floyd-Steinberg the gradient shows smooth dot patterns
        // across the full width.  Without dithering it would be a hard black/white split.
        testDitheringButton.setOnClickListener {
            val (width, height) = mPreferencesController?.getScreenSizePixels()
                ?: Pair(800, 480)

            // Build gradient pixels: column x maps linearly from gray=0 (black) to gray=255 (white)
            val pixels = IntArray(width * height) { idx ->
                val x = idx % width
                val gray = (x * 255) / (width - 1)
                Color.rgb(gray, gray, gray)
            }
            val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

            openFileOutput(GeneratedImageFilename, Context.MODE_PRIVATE).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            startActivity(Intent(this, NfcFlasher::class.java))
        }

        // This button correctly keeps its original function to pick a local image
        imageFilePickerCTA.setOnClickListener {
            val screenSizePixels = this.mPreferencesController?.getScreenSizePixels()!!
            CropImage.activity()
                .setGuidelines(CropImageView.Guidelines.ON)
                .setAspectRatio(screenSizePixels.first, screenSizePixels.second)
                .setRequestedSize(screenSizePixels.first, screenSizePixels.second, CropImageView.RequestSizeOptions.RESIZE_EXACT)
                .start(this)
        }

        // Settings screen listeners
        settingsButton.setOnClickListener {
            findViewById<View>(R.id.main_screen_content).visibility = View.GONE
            settingsLayout.visibility = View.VISIBLE
        }

        saveUrlButton.setOnClickListener {
            val urlEditText: EditText = findViewById(R.id.url_edit_text)
            val newUrl = urlEditText.text.toString().trim()
            mPreferencesController?.getPreferences()?.edit()?.putString(PrefKeys.BaseUrl, newUrl)?.apply()
             Toast.makeText(this, "URL saved!", Toast.LENGTH_SHORT).show()
            viewModel.setBaseUrl(newUrl)

            settingsLayout.visibility = View.GONE
            findViewById<View>(R.id.main_screen_content).visibility = View.VISIBLE
        }

        // Other original listeners
        screenSizeChangeInvite.setOnClickListener {
            this.mPreferencesController?.showScreenSizePicker(fun(updated: String): Void? {
                this.updateScreenSizeDisplay(updated)
                return null
            })
        }

        mReFlashButton.setOnClickListener {
            if (mHasReFlashableImage) {
                val navIntent = Intent(this, NfcFlasher::class.java)
                startActivity(navIntent)
            } else {
                Toast.makeText(this, "There is no image to re-flash!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // We check the image for re-flashing every time the screen is shown
        checkReFlashAbility()
    }

    private fun setupProtocolSpinner() {
        val spinner = findViewById<Spinner>(R.id.spinner_protocol_main) ?: return
        val modes   = ProtocolMode.values()
        val names   = modes.map { it.displayName }.toTypedArray()

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val saved = Preferences(this).getProtocolMode()
        spinner.setSelection(saved.ordinal, false)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                val mode = ProtocolMode.fromOrdinal(pos)
                Preferences(this@MainActivity).saveProtocolMode(mode)
                Log.d("MainActivity", "Protocol mode → ${mode.name}")
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE) {
            val result = CropImage.getActivityResult(resultData)
            if (resultCode == Activity.RESULT_OK) {
                val croppedBitmap = result?.getBitmap(this)
                if (croppedBitmap != null) {
                    openFileOutput(GeneratedImageFilename, Context.MODE_PRIVATE).use { fileOutStream ->
                        croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, fileOutStream)
                        fileOutStream.close()
                        // After picking a local image, we can optionally go straight to the flasher
                        val navIntent = Intent(this, NfcFlasher::class.java)
                        startActivity(navIntent)
                    }
                }
            } else if (resultCode == CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE) {
                Log.e("Crop image callback", "Crop error: ${result?.error?.message}")
            }
        }
    }

    private fun updateScreenSizeDisplay(updated: String?) {
        var screenSizeStr = updated
        if (screenSizeStr == null) {
            screenSizeStr = this.mPreferencesController?.getPreferences()
                ?.getString(Constants.PreferenceKeys.DisplaySize, DefaultScreenSize)
        }
        findViewById<TextView>(R.id.currentDisplaySize).text = screenSizeStr ?: DefaultScreenSize
    }

    private fun checkReFlashAbility() {
        val lastGeneratedFile = getFileStreamPath(GeneratedImageFilename)
        val reFlashImagePreview: ImageView = findViewById(R.id.reflashButtonImage)
        if (lastGeneratedFile.exists()) {
            mHasReFlashableImage = true
            reFlashImagePreview.setImageURI(null) // Force refresh
            reFlashImagePreview.setImageURI(Uri.fromFile((lastGeneratedFile)))
        } else {
            mReFlashButton.setCardBackgroundColor(Color.DKGRAY)
            val drawableImg = resources.getDrawable(android.R.drawable.stat_sys_warning, null)
            reFlashImagePreview.setImageDrawable(drawableImg)
        }
    }
}