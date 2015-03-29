package io.quantumdb.core.schema.definitions;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

@Data
@EqualsAndHashCode(exclude = { "parent" })
@Setter(AccessLevel.NONE)
public class Sequence {

	private String name;
	private Catalog parent;

	public Sequence(String name) {
		this.name = name;
	}

	void setParent(Catalog parent) {
		this.parent = parent;
	}

	public Sequence rename(String newName) {
		this.name = newName;
		return this;
	}

}
