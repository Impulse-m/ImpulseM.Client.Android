package net.impulsem.transport.auth;


public interface Clock {

    Clock SYSTEM = new Clock() {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }
    };


    long nowMillis();
}
