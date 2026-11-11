package vn.disciples.minions.bedrock;

/** Một nút của Floodgate SimpleForm: chữ + ảnh tùy chọn (imageType = "URL" hoặc "PATH", rỗng = không ảnh). */
public final class FormButton {
    public final String text;
    public final String imageType;
    public final String imageValue;

    public FormButton(String text, String imageType, String imageValue) {
        this.text = text;
        this.imageType = imageType == null ? "" : imageType;
        this.imageValue = imageValue == null ? "" : imageValue;
    }

    public FormButton(String text) { this(text, "", ""); }

    public boolean hasImage() { return !imageValue.isEmpty() && !imageType.isEmpty(); }
}
