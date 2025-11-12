package dev.braintrust.eval;

import java.util.List;
import java.util.Optional;

/** A dataset held entirely in memory */
class DatasetInMemoryImpl<INPUT, OUTPUT> implements Dataset<INPUT, OUTPUT> {
    private final List<DatasetCase<INPUT, OUTPUT>> cases;
    private final String id;

    DatasetInMemoryImpl(List<DatasetCase<INPUT, OUTPUT>> cases) {
        this.cases = List.copyOf(cases);
        id = "in-memory-dataset<" + this.cases.hashCode() + ">";
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String version() {
        return "0";
    }

    @Override
    public Cursor<DatasetCase<INPUT, OUTPUT>> openCursor() {
        return new Cursor<>() {
            int nextIndex = 0;
            boolean closed = false;

            @Override
            public Optional<DatasetCase<INPUT, OUTPUT>> next() {
                if (closed) {
                    throw new IllegalStateException("this method may not be invoked after close");
                } else if (nextIndex < cases.size()) {
                    return Optional.of(cases.get(nextIndex++));
                } else {
                    return Optional.empty();
                }
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }
}
