package com.example.shadowproxy.metrics;

public interface ShadowGauges {
    ShadowGauges EMPTY =
            new ShadowGauges() {
                @Override
                public int queueDepth() {
                    return 0;
                }

                @Override
                public int queueCapacity() {
                    return 0;
                }

                @Override
                public long bytesUsed() {
                    return 0;
                }

                @Override
                public long bytesCapacity() {
                    return 0;
                }

                @Override
                public int inFlight() {
                    return 0;
                }

                @Override
                public int workers() {
                    return 0;
                }

                @Override
                public double currentAdmitProbability() {
                    return 0;
                }

                @Override
                public String breakerState() {
                    return "closed";
                }
            };

    int queueDepth();

    int queueCapacity();

    long bytesUsed();

    long bytesCapacity();

    int inFlight();

    int workers();

    double currentAdmitProbability();

    String breakerState();
}
