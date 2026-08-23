import sair.sys.SairCons;
public class RainbowHello {
    public void hello(String msg) {
        String[] colors = {
            "\u001B[31m", "\u001B[33m", "\u001B[32m", "\u001B[36m", "\u001B[34m", "\u001B[35m"
        };
        String reset = "\u001B[0m";
        String text = (msg != null && !msg.isEmpty()) ? msg : "Hello World";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            sb.append(colors[i % 6]).append(text.charAt(i));
        }
        sb.append(reset);
        SairCons.println(sb.toString());
    }
}