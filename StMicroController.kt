package com.imobile3.pos.hardware.printer.stmicro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.imobile3.pos.hardware.printer.stmicro.objects.Logging.TAG
import com.imobile3.pos.hardware.printer.stmicro.objects.StMicroPrinterHelper
import com.imobile3.pos.hardware.printer.stmicro.objects.StMicroPrinterHelper.getPaddedWidth
import com.imobile3.pos.library.interfaces.printer.iM3Printer
import com.imobile3.pos.library.interfaces.printer.iM3PrinterCallbacks
import com.imobile3.pos.library.interfaces.printer.iM3PrinterReceipt
import com.imobile3.pos.library.interfaces.printer.iM3PrinterReceiptComponent
import com.imobile3.pos.library.interfaces.printer.iM3PrinterReceiptComponent.BarcodeType
import com.imobile3.pos.library.interfaces.printer.iM3PrinterReceiptComponent.Justification
import com.imobile3.pos.library.interfaces.printer.iM3PrinterReceiptComponent.Justification.Center
import com.imobile3.pos.library.interfaces.printer.iM3PrinterReceiptComponent.TextFont
import com.imobile3.pos.library.interfaces.printer.iM3PrinterReceiptComponent.TextSize
import com.imobile3.pos.library.interfaces.printer.iM3PrinterReceiptComponent.TextStyle
import com.imobile3.pos.library.utils.BarcodeHelper
import com.imobile3.pos.library.utils.ImageHelper
import com.imobile3.pos.library.utils.LogHelper
import com.imobile3.pos.library.utils.PrintFormatHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Essentially an SDK for the ST-P398C printer that is integrated on the Bleep TS813 POS. The
 * ST-P398C is an extremely basic ascii printer connected via usb. Commands are sent to it in the
 * form of a [ByteArray].
 */
class StMicroController(
    val context: Context?,
    val device: SupportedDevice?,
    val connectionType: SupportedConnectionType?,
    val address: String?,
    val isShared: Boolean,
    val callbacks: iM3PrinterCallbacks?
) : iM3Printer {
    // Android USB classes
    private val vendorId = device?.printerSpecs?.usbVendorId
    private val productId = device?.printerSpecs?.usbProductId
    private var usbInterface: UsbInterface? = null
    private var connection: UsbDeviceConnection? = null
    private var outputEndpoint: UsbEndpoint? = null

    private val buffer = ByteArrayOutputStream()
    private val bufferMax = 8 * 1024 // I'm copying this value from the Seiko Controller

    private var hasPrinted = false

    override fun printText(text: String?, cutPaper: Boolean) {
        LogHelper.write(TAG, "StMicroController.printText($text, $cutPaper)")

        val connection = getConnectionOrNull()

        if (connection != null) {
            PrintTextTask(text, cutPaper).apply {
                name = "iM3Printer Print Text"
                start()
            }
        } else {
            // If the connection is still null after attempting to connect then report an error
            val errorMsg = "Failed to establish a connection to the " +
                    "${device?.supportedHardware?.displayName ?: "Unknown"} printer."
            LogHelper.write(TAG, errorMsg)
            callbacks?.iM3PrinterOnError(errorMsg)
        }
    }

    override fun printReceipt(receipt: iM3PrinterReceipt?) {
        LogHelper.write(TAG, "StMicroController.printReceipt($receipt)")

        val connection = getConnectionOrNull()

        if (connection != null) {
            PrintReceiptTask(receipt).apply {
                name = "iM3Printer Print Receipt"
                start()
            }
        } else {
            // If the connection is still null after attempting to connect then report an error
            val errorMsg = "Failed to establish a connection to the " +
                    "${device?.supportedHardware?.displayName ?: "Unknown"} printer."
            LogHelper.write(TAG, errorMsg)
            callbacks?.iM3PrinterOnError(errorMsg)
        }
    }

    override fun openCashDrawer() {
        // no-op this printer does not have a cash drawer
    }

    private fun getConnectionOrNull(): UsbDeviceConnection? {
        return connection ?: connect()
    }

    private fun connect(): UsbDeviceConnection? {
        val manager: UsbManager =
            context?.getSystemService(Context.USB_SERVICE) as UsbManager

        val deviceMap: HashMap<String, UsbDevice> = manager.deviceList

        // Look for our printer by it's usb vendor and product id's
        for (usbDevice in deviceMap.values) {
            if (usbDevice.vendorId == vendorId && usbDevice.productId == productId) {
                // Their should only be one interface present
                usbInterface = usbDevice.getInterface(0)

                usbInterface?.let { usbInterface ->
                    // Every UsbInterface has multiple endpoints to communicate with different
                    // purposes. For the bleep printer there are only 2 (input and output) and we are
                    // only interested in the output endpoint.
                    for (i in 0 until usbInterface.endpointCount) {
                        val endpoint = usbInterface.getEndpoint(i)

                        // Looking for the output endpoint specifically
                        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                            && endpoint.direction == UsbConstants.USB_DIR_OUT) {
                            outputEndpoint = endpoint
                        }
                    }

                    // Establish communication with the printer
                    connection = manager.openDevice(usbDevice)
                    connection?.claimInterface(usbInterface, true)
                }
                break
            }
        }

        return connection
    }

    private inner class PrintTextTask(val textToPrint: String?, val cutPaper: Boolean): Thread() {
        override fun run() {
            runBlocking {
                launch(Dispatchers.IO) {
                    try {
                        setCodeTable()

                        val textComponent = iM3PrinterReceiptComponent().apply {
                            this.text = textToPrint
                            justification = Justification.Center
                            textFont = TextFont.Normal
                            textSize = TextSize.Normal
                            textStyle = TextStyle.Normal
                        }
                        addText(textComponent)

                        if (cutPaper) {
                            addFeedLines(5)
                            addCut()
                        }

                        // Send the buffer of commands to the printer as a ByteArray
                        flushBuffer()

                        // Callbacks must be invoked on the main thread
                        withContext(Dispatchers.Main) {
                            callbacks?.iM3PrinterOnPrintComplete()
                        }
                    } catch (e: Exception) {
                        LogHelper.write(TAG, "PrintTextTask -> Failed to print text: " +
                                e.localizedMessage
                        )

                        // Callbacks must be invoked on the main thread
                        withContext(Dispatchers.Main) {
                            callbacks?.iM3PrinterOnError(e.localizedMessage)
                        }
                    }

                    // Disconnect the printer when we're done with it
                    disconnect()
                }
            }
        }
    }

    private inner class PrintReceiptTask(val receipt: iM3PrinterReceipt?) : Thread() {
        override fun run() {
            runBlocking {
                launch(Dispatchers.IO) {
                    try {
                        val receipts = receipt?.split(
                            device?.printerSpecs?.maxComponentsPerReceipt ?: 0)

                        if (receipts != null) {
                            for (receipt in receipts) {
                                setCodeTable()

                                bufferReceipt(receipt)

                                if (receipt.isCutReceipt) {
                                    addFeedLines(5)
                                    addCut()
                                }
                            }

                            // Send the buffer of commands to the printer as a ByteArray
                            flushBuffer()
                        }

                        // Callbacks must be invoked on the main thread
                        withContext(Dispatchers.Main) {
                            callbacks?.iM3PrinterOnPrintComplete()
                        }
                    } catch(e: Exception) {
                        LogHelper.write(TAG, "PrintReceiptTask() -> Failed to print " +
                                "receipt: ${e.localizedMessage}")

                        // Callbacks must be invoked on the main thread
                        withContext(Dispatchers.Main) {
                            callbacks?.iM3PrinterOnError(e.localizedMessage)
                        }
                    }

                    // Disconnect the printer when we're done with it
                    disconnect()
                }
            }
        }
    }

    private fun addText(component: iM3PrinterReceiptComponent) {
        with (component) {
            val maxCharWidth = getMaxCharWidth(textFont, textSize)
            val formattedText = PrintFormatHelper.getFormattedText(component, maxCharWidth)

            setLineSpacing(getTextLineHeight(textFont, textSize))

            addFeedLinesIfNeeded(1)

            addJustification(justification)
            addTextFont(textFont)
            addTextSize(textSize)
            addTextStyle(textStyle)

            buffer(getByteArray(getHex(formattedText)))
        }
    }

    private fun bufferReceipt(receipt: iM3PrinterReceipt) {
        for (component in receipt.components) {
            with (component) {
                when (type) {
                    iM3PrinterReceiptComponent.Type.Text -> addText(component)
                    iM3PrinterReceiptComponent.Type.Bitmap -> addBitmap(bitmap, justification)
                    iM3PrinterReceiptComponent.Type.Barcode -> {
                        addBarcode(barcodeType, barcodeContent, justification)
                    }
                    iM3PrinterReceiptComponent.Type.BarcodeFullWidth -> {
                        addBarcode(barcodeType, barcodeContent, justification)
                    }
                    iM3PrinterReceiptComponent.Type.Divider -> addDivider(dividerHeight)
                    iM3PrinterReceiptComponent.Type.UnderscoreDivider -> {
                        addUnderscoreDivider(textFont, textSize, textStyle)
                    }
                    iM3PrinterReceiptComponent.Type.LineFeed -> addFeedLines(component.feedLines)
                    iM3PrinterReceiptComponent.Type.Cut -> addCut()
                    iM3PrinterReceiptComponent.Type.Qrcode -> addQrcode(qrcodeContent, justification)
                    null -> {} // no-op
                }
            }
        }
    }

    private fun setLineSpacing(lineHeight: Int) {
        buffer(getByteArray("${AsciiControlCodes.SET_LINE_SPACING}${getHex(lineHeight)}"))
    }

    private fun getMaxCharWidth(textFont: TextFont, textSize: TextSize): Int {
        if (device != null) {
            with (device.printerSpecs) {
                // If we are printing big or wide text we can only fit half the characters
                var widthMultiplier = 1
                if (isTextSizeSupported) {
                    if (textSize == TextSize.Double || textSize == TextSize.Wide) {
                        widthMultiplier = 2
                    }
                }

                // The width will be the total width of the receipt divided by the width of each
                // character. So we get the max amount of characters that can fit on one line.
                return if (textFont == TextFont.Normal) {
                    pageWidth / (fontWidthA * widthMultiplier)
                } else {
                    pageWidth / (fontWidthB * widthMultiplier)
                }
            }
        } else {
            // The device really shouldn't be null at this point. We need to fix this class if it is.
            LogHelper.write(TAG, "getMaxCharWidth -> device is null returning worst " +
                    "case scenario of 24")
            return 24
        }
    }

    private fun getTextLineHeight(textFont: TextFont, textSize: TextSize): Int {
        if (device != null) {
            with (device.printerSpecs) {
                var heightMultiplier = 1
                if (isTextSizeSupported) {
                    if (textSize == TextSize.Tall) {
                        heightMultiplier = 2
                    }
                }

                val heightPadding = heightMultiplier * 5

                return if (textFont == TextFont.Normal) {
                    (fontHeightA * heightMultiplier) + heightPadding
                } else {
                    (fontHeightB * heightMultiplier) + heightPadding
                }
            }
        } else {
            // The device really shouldn't be null at this point. We need to fix this class if it is.
            LogHelper.write(TAG, "getTextLineHeight -> device is null returning worst " +
                    "case scenario of 58")
            return 58
        }
    }

    private fun addJustification(justification: Justification) {
        when (justification) {
            Justification.Left -> {
                buffer(getByteArray(
                    "${AsciiControlCodes.SELECT_JUSTIFICATION}${getHex(0)}"))
            }
            Justification.Center -> {
                buffer(getByteArray(
                    "${AsciiControlCodes.SELECT_JUSTIFICATION}${getHex(1)}"))
            }
            Justification.Right -> {
                buffer(getByteArray(
                    "${AsciiControlCodes.SELECT_JUSTIFICATION}${getHex(2)}"))
            }
            else -> {
                buffer(getByteArray(
                    "${AsciiControlCodes.SELECT_JUSTIFICATION}${getHex(0)}"))
            }
        }
    }

    private fun addTextFont(textFont: TextFont) {
        if (textFont == TextFont.Normal) {
            buffer(getByteArray("${AsciiControlCodes.SELECT_MODE}00")) // Font A
        } else {
            buffer(getByteArray("${AsciiControlCodes.SELECT_MODE}01")) // Font B (Smaller)
        }
    }

    private fun addTextSize(textSize: TextSize) {
        // This printer only supports tall and wide settings but not both at the same time. So if
        // TextSize is not Tall or Wide then we default to Normal.
        when (textSize) {
            TextSize.Tall ->
                buffer(getByteArray("${AsciiControlCodes.SET_CHARACTER_SIZE}01"))

            TextSize.Wide ->
                buffer(getByteArray("${AsciiControlCodes.SET_CHARACTER_SIZE}10"))

            else ->
                buffer(getByteArray("${AsciiControlCodes.SET_CHARACTER_SIZE}00"))
        }
    }

    private fun addTextStyle(textStyle: TextStyle) {
        when (textStyle) {
            TextStyle.Normal -> {
                buffer(getByteArray("${AsciiControlCodes.SET_BOLD}00")) // Turn off bold
                buffer(getByteArray("${AsciiControlCodes.SET_UNDERLINE}00")) // Turn off underline
                buffer(getByteArray("${AsciiControlCodes.SET_INVERSE}00")) // Turn off inverse
            }
            TextStyle.Bold -> {
                buffer(getByteArray("${AsciiControlCodes.SET_BOLD}01")) // Turn on bold
                buffer(getByteArray("${AsciiControlCodes.SET_UNDERLINE}00")) // Turn off underline
                buffer(getByteArray("${AsciiControlCodes.SET_INVERSE}00")) // Turn off inverse
            }
            TextStyle.Underline -> {
                buffer(getByteArray("${AsciiControlCodes.SET_BOLD}00")) // Turn off bold
                buffer(getByteArray("${AsciiControlCodes.SET_UNDERLINE}02")) // Turn on underline
                buffer(getByteArray("${AsciiControlCodes.SET_INVERSE}00")) // Turn off inverse
            }
            TextStyle.Inverse -> {
                // The ST_P398C printer prints white on black but then errors immediately afterwards
                // this might be a firmware issue :(
                buffer(getByteArray("${AsciiControlCodes.SET_BOLD}00")) // Turn off bold
                buffer(getByteArray("${AsciiControlCodes.SET_UNDERLINE}00")) // Turn off underline
                buffer(getByteArray("${AsciiControlCodes.SET_INVERSE}00")) // Turn off inverse
            }
            TextStyle.Italic -> {
                // The ST_P398C printer does not support italics :(
                buffer(getByteArray("${AsciiControlCodes.SET_BOLD}00")) // Turn off bold
                buffer(getByteArray("${AsciiControlCodes.SET_UNDERLINE}00")) // Turn off underline
                buffer(getByteArray("${AsciiControlCodes.SET_INVERSE}00")) // Turn off inverse
            }
        }
    }

    /**
     * Converts a string into hex. The resulting hex string is a string of two digit hex characters.
     * Each two digit hex character represents one regular ascii character. For example:
     * ```
     * val result = getHex("Hello")
     * println(result) // prints -> 48656C6C6F
     * // H=48, e=65, l=6C, l=6C, o=6F
     * ```
     * This is step one of a two step process to convert a [String] into an array of [Byte] to be
     * sent to the printer. Next we'll call [getByteArray]
     */
    private fun getHex(string: String?): String {
        string ?: return ""

        val sb = StringBuilder()
        for (char in string) {
            sb.append(String.format("%02X", char.code))
        }

        return sb.toString()
    }

    private fun getHex(int: Int): String = String.format("%02X", int)

    private fun getByteArray(hex: String): ByteArray {
        // Each hexadecimal character is comprised of two hexadecimal digits. So every two
        // characters in our hex string will be converted to one Byte.
        val bytes = ByteArray(hex.length / 2)

        for (i in bytes.indices) {
            // Grab two hex characters at a time from our string and split them up
            val firstIndex = i * 2
            val secondIndex = i * 2 + 1
            val firstHalf: String = hex.substring(firstIndex, firstIndex + 1)
            val secondHalf: String = hex.substring(secondIndex, secondIndex + 1)

            try {
                // Each hex character represents 4 bits and we want to combine them to make a byte of
                // 8 bits. Start by shifting the first character left 4 bits to make room for the
                // second hex character.
                val firstInt = Integer.parseInt(firstHalf, 16) shl 4
                val secondInt = Integer.parseInt(secondHalf, 16)

                // Now that we've bit shifted the first hex we can combine the two blocks of 4 bits
                // into one byte with an inclusive "or" operator
                bytes[i] = (firstInt or secondInt).toByte()
            } catch(e: Exception) {
                if (e is NumberFormatException) {
                    LogHelper.write(TAG, "getByteArray() -> Failed to convert hex character" +
                            "to Byte with the following error: ${e.localizedMessage}")
                } else {
                    LogHelper.write(TAG, "getByteArray() -> ${e.localizedMessage}")
                }
            }
        }

        return bytes
    }

    private fun getByteArray(hex: String, bitmap: Bitmap): ByteArray {
        val pixelArray = StMicroPrinterHelper.decodeBitmap(bitmap)

        val bos = ByteArrayOutputStream()
        bos.write(getByteArray(hex))
        bos.write(pixelArray)

        return bos.toByteArray()
    }

    private fun buffer(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) {
            return
        }

        if ((bytes.size + buffer.size()) >= bufferMax) {
            flushBuffer()
        }

        buffer.write(bytes)
    }

    private fun flushBuffer() {
        if (buffer.size() == 0) {
            return
        }

        val bytes = buffer.toByteArray()
        connection?.bulkTransfer(outputEndpoint, bytes, bytes.size, 0)
        buffer.reset()
    }

    private fun setCodeTable() {
        // Make sure the [USA, Standard Europe] character code table is selected
        buffer(getByteArray("${AsciiControlCodes.SELECT_CODE_TABLE}00"))
    }

    private fun addFeedLines(lines: Int) {
        for (i in 1..lines) {
            buffer(getByteArray(AsciiControlCodes.LF))
        }
    }

    private fun addCut() {
        buffer(getByteArray(AsciiControlCodes.PARTIAL_CUT))
    }

    private fun addFeedLinesIfNeeded(lines: Int) {
        if (hasPrinted) {
            addFeedLines(1)
        } else {
            hasPrinted = true
        }
    }

    private fun addBitmap(bitmap: Bitmap, justification: Justification) {
        // Configuring the bitmap before printing
        val widthAfterPadding = getPaddedWidth(bitmap.width)
        val maxPageWidth = device?.printerSpecs?.pageWidth ?: 576

        var configuredBitmap = bitmap

        // Scale the bitmap if it doesn't fit on the page
        if (widthAfterPadding > maxPageWidth) {
            configuredBitmap = ImageHelper.scaleBitmap(configuredBitmap, maxPageWidth)
        }

        // printer doesn't like alpha channels, so set to white
        configuredBitmap = ImageHelper.alphaToWhite(configuredBitmap)

        // make sure we're dealing with RGB_565
        configuredBitmap = ImageHelper.reduceConfig(configuredBitmap)

        addJustification(justification)
        addFeedLinesIfNeeded(1)

        bufferBitmap(configuredBitmap)
    }

    /**
     * Adds the command to print a raster image to the buffer. The parameters of the command are:<p>
     *
     * m xL xH yL yH d1..dk
     *
     * m Sets optional scaling parameters (we're not gonna use it).
     *
     * xL Is how many bytes wide our raster image will be.
     *
     * xH Does essentially the same thing and we won't use it.
     *
     * xY Is how how many bits (not bytes!) tall our raster image will be.
     *
     * d1..dk Is a bunch of bits representing the image itself. 0 for white and 1 for black.
     *
     * There are more details about how a bitmap is converted into a byte array in the Kdoc for
     * [StMicroPrinterHelper.decodeBitmap].
     */
    private fun bufferBitmap(bitmap: Bitmap) {
        val hexString = StringBuilder()
            .append(AsciiControlCodes.PRINT_BITMAP)
            .append(getHex(0)) // No scaling
            .append(getHex(getPaddedWidth(bitmap.width) / 8))// xL = Width after padding / 8;
            .append(getHex(0)) // xH = 0
            .append(getHex(bitmap.height)) // yL = height
            .append(getHex(0)) // yH = 0
            .toString()

        buffer(getByteArray(hexString, bitmap))
    }

    private fun addBarcode(type: BarcodeType, content: String, justification: Justification) {
        val contentTextSize = 23
        var width = contentTextSize * content.length * 2
        if (width > (device?.printerSpecs?.pageWidth ?: 576)) {
            width = (device?.printerSpecs?.pageWidth ?: 576) - 100
        }
        val height = 80

        val bitmap = BarcodeHelper
            .getBarcode(type, content, width, height, false, contentTextSize)

        addJustification(justification)
        addFeedLinesIfNeeded(1)

        bufferBitmap(bitmap)
    }

    private fun addDivider(height: Int) {
        val pageWidth = device?.printerSpecs?.pageWidth ?: 576
        val bitmap = Bitmap.createBitmap(pageWidth, height, Bitmap.Config.RGB_565)
        bitmap.eraseColor(Color.BLACK)

        addFeedLinesIfNeeded(2)

        bufferBitmap(bitmap)
    }

    private fun addUnderscoreDivider(
        textFont: TextFont,
        textSize: TextSize,
        textStyle: TextStyle
    ) {
        val maxWidth = getMaxCharWidth(textFont, textSize)

        setLineSpacing(getTextLineHeight(textFont, textSize))

        val text = String(CharArray(maxWidth)).replace("\u0000", "_")

        addFeedLinesIfNeeded(1)
        addJustification(Center)
        addTextFont(textFont)
        addTextSize(textSize)
        addTextStyle(textStyle)

        buffer(getByteArray(getHex(text)))
    }

    private fun addQrcode(content: String, justification: Justification) {
        var size = (device?.printerSpecs?.pageWidth ?: 576) / 3
        // The manual says the maximum height in pixels is 255 but in practice it fails to print
        // anything larger than 192 :(
        if (size > 192) {
            size = 192
        }
        val bitmap = BarcodeHelper.getQrCode(content, size)
        addBitmap(bitmap, justification)
    }

    private fun disconnect() {
        connection?.releaseInterface(usbInterface)
        connection?.close()
        connection = null
        usbInterface = null
        outputEndpoint = null
    }
}
