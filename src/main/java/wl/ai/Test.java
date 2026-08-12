package wl.ai;

import java.io.IOException;
import java.util.Map;

public class Test {
    public static void main(String[] args) throws IOException {
	// TODO Auto-generated method stub
	LLMService service = new LLMService();
	Map<String, Object> res = service.callToMap("you are pro medical coder",
		"give me the icd code for the kne pain");
	System.out.println(res);
    }

}
