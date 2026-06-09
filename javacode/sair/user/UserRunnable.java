package sair.user;

interface UserRunnable {
    Object main(String funcName, String args);

    String[] help();

    void exit();
}
