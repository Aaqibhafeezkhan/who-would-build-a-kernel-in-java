package duke.user;

class Args {
    static void main(String[] args) {
        System.out.println(args.length + " arguments");
        for (String arg : args) {
            System.out.println("[" + arg + "]");
        }
    }
}
