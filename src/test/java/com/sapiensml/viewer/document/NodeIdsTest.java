package com.sapiensml.viewer.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeIdsTest {

    static final String ID = "42099fec-ce6e-4ab6-a537-0369b6b6f7d2";

    @Test
    void acceptsNodeIdsAndNodeReferences() {
        assertThat(NodeIds.normalize(ID)).contains(ID);
        assertThat(NodeIds.normalize(" " + ID.toUpperCase() + " ")).contains(ID);
        assertThat(NodeIds.normalize("workspace://SpacesStore/" + ID)).contains(ID);
        assertThat(NodeIds.normalize("WORKSPACE://SpacesStore/" + ID)).contains(ID);
    }

    @Test
    void refusesEverythingElse() {
        assertThat(NodeIds.normalize(null)).isEmpty();
        assertThat(NodeIds.normalize("")).isEmpty();
        assertThat(NodeIds.normalize("-root-")).isEmpty();
        assertThat(NodeIds.normalize("-my-")).isEmpty();
        assertThat(NodeIds.normalize(ID + "/children")).isEmpty();
        assertThat(NodeIds.normalize("../people/-me-")).isEmpty();
        assertThat(NodeIds.normalize("archive://SpacesStore/" + ID)).isEmpty();
        assertThat(NodeIds.normalize(ID + ";1.0")).isEmpty();
        assertThatThrownBy(() -> NodeIds.require("x")).isInstanceOf(DocumentExceptions.InvalidNodeId.class);
    }

}
