# Add project specific ProGuard rules here.
# For more details, see https://developer.android.com/build/shrink-code

# PdfBox-Android's JPXFilter optionally calls into com.gemalto.jp2 for JPEG2000 images, a
# separate proprietary decoder we don't bundle (JPEG2000 in a PDF is rare in practice).
-dontwarn com.gemalto.jp2.**
