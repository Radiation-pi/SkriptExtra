package re.imc.skriptextra.storage;

/** Serialized by Skript; a null value means deletion. */
public record VariableChange(String name, String type, byte[] value) {
    public VariableChange {
        if (name == null || (value != null && type == null))
            throw new IllegalArgumentException("Invalid serialized variable");
        value = value == null ? null : value.clone();
    }
}
