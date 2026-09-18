package kr.co.cking.support;

/** 공개 응답에서 개인정보를 원본 변경 없이 마스킹하는 보조 도구다. */
public final class PersonalInfoMasker {

    /** 인스턴스 생성을 막아 정적 마스킹 기능만 제공한다. */
    private PersonalInfoMasker() {
    }

    /** 이름의 가운데 글자를 별표로 바꿔 공개용 이름을 만든다. */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() == 1) {
            return "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    /** 하이픈으로 구분된 전화번호의 가운데 자리를 별표로 바꿔 공개용 번호를 만든다. */
    public static String maskPhone(String phone) {
        if (phone == null || !phone.matches("^\\d{3}-\\d{3,4}-\\d{4}$")) {
            return phone;
        }
        String[] parts = phone.split("-");
        return parts[0] + "-****-" + parts[2];
    }
}
